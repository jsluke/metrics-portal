/**
 * Copyright 2017 Smartsheet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arpnetworking.metrics.portal.alerts.impl;

import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Terminated;
import akka.cluster.sharding.ShardRegion;
import akka.pattern.PatternsCS;
import akka.persistence.AbstractPersistentActorWithTimers;
import com.arpnetworking.kairos.client.models.MetricsQueryResponse;
import com.arpnetworking.metrics.portal.alerts.AlertRepository;
import com.arpnetworking.mql.grammar.AlertTrigger;
import com.arpnetworking.mql.grammar.CollectingErrorListener;
import com.arpnetworking.mql.grammar.ExecutionException;
import com.arpnetworking.mql.grammar.MqlLexer;
import com.arpnetworking.mql.grammar.MqlParser;
import com.arpnetworking.mql.grammar.QueryRunner;
import com.arpnetworking.mql.grammar.TimeSeriesResult;
import com.arpnetworking.steno.Logger;
import com.arpnetworking.steno.LoggerFactory;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.inject.Injector;
import models.internal.Alert;
import models.internal.Organization;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.joda.time.Period;
import scala.concurrent.duration.Duration;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Actor responsible for executing and evaluating an alert query.
 *
 * @author Brandon Arp (brandon dot arp at smartsheet dot com)
 */
public class AlertExecutor extends AbstractPersistentActorWithTimers {
    /**
     * Public constructor.
     *
     * @param alertRepository an alert repository
     * @param queryRunnerProvider a query runner provider
     */
    @Inject
    public AlertExecutor(final AlertRepository alertRepository, final Provider<QueryRunner> queryRunnerProvider, final Injector injector) {
        _alertRepository = alertRepository;
        _queryRunner = queryRunnerProvider;
        _injector = injector;
        LOGGER.debug()
                .setMessage("Starting alert executor")
                .addData("name", self().path().name())
                .log();
    }

    @Override
    public Receive createReceiveRecover() {
        return receiveBuilder()
                .match(InstantiateAlert.class, message -> {
                    _alertId = message.getAlertId();
                    LOGGER.debug()
                            .setMessage("Instantiating alert from recovery")
                            .addData("alertId", _alertId)
                            .log();
                    loadAlert();
                })
                .build();
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(InstantiateAlert.class, message -> {
                    // We have already persisted the ID and recovered it
                    if (_alertId != null) {
                        return;
                    }
                    persist(message, msg -> {
                        _alertId = message.getAlertId();
                        LOGGER.debug()
                                .setMessage("Instantiating alert")
                                .addData("alertId", _alertId)
                                .log();
                        loadAlert();
                    });
                })
                .match(RefreshAlert.class, refresh -> {
                    loadAlert();
                })
                .match(ExecuteAlert.class, executeAlert -> {
                    LOGGER.debug()
                            .setMessage("Executing alert")
                            .addData("alertId", _alertId)
                            .log();
                    PatternsCS.pipe(_queryRunner.get().visitStatement(_parsedStatement), context().dispatcher()).to(self());
                })
                .match(SendTo.class, sendTo -> {
                    if (_alertId == null) {
                        self().tell(new InstantiateAlert(sendTo.getAlertId()), self());
                    }
                    self().tell(sendTo.getPayload(), getSender());
                })
                .match(TimeSeriesResult.class, series -> {
                    if (series.getErrors().size() > 0) {
                        LOGGER.warn()
                                .setMessage("Errors when executing alert query")
                                .addData("alertId", _alertId)
                                .addData("errors", series.getErrors())
                                .log();
                    }

                    LOGGER.debug()
                            .setMessage("Alert execution completed")
                            .addData("alertId", _alertId)
                            .log();

                    final List<MetricsQueryResponse.Query> queries = series.getResponse().getQueries();
                    for (final MetricsQueryResponse.Query query : queries) {
                        for (MetricsQueryResponse.QueryResult queryResult : query.getResults()) {
                            for (AlertTrigger alert : queryResult.getAlerts()) {
                                final String notificationId = hashAlert(alert);
                                final ActorRef notificationActor = getOrLaunchActor(notificationId);
                                // If it's shutting down, we enqueue to the pending messages for that actor
                                if (_shuttingDownNotifications.contains(notificationActor)) {
                                    _pendingMessages.put(notificationId, alert);
                                } else {
                                    notificationActor.tell(alert, self());
                                }
                            }
                        }
                    }
                })
                .match(NotificationActor.ShuttingDown.class, shuttingDown -> {
                    _shuttingDownNotifications.add(context().sender());
                    context().sender().tell(NotificationActor.ShuttingDownAck.getInstance(), self());
                })
                .match(Terminated.class, terminated -> {
                    final ActorRef dead = terminated.actor();
                    if (!_shuttingDownNotifications.contains(dead)) {
                        LOGGER.warn()
                                .setMessage("Notification actor died without sending shutdown")
                                .addData("notificationActor", dead)
                                .log();
                    }
                    _shuttingDownNotifications.remove(dead);
                    // Check to see if there are any pending messages that would require re-launching
                    final String notificationId = _notificationActors.inverse().get(dead);
                    _notificationActors.remove(notificationId);
                    if (!_pendingMessages.get(notificationId).isEmpty()) {
                        getOrLaunchActor(notificationId);
                    }
                })
                .build();
    }

    private ActorRef getOrLaunchActor(String notificationId) {
        return _notificationActors.computeIfAbsent(
                notificationId,
                hash -> {
                    final ActorRef actor = context().actorOf(
                            NotificationActor.props(_alertId, _alertRepository, _injector),
                            notificationId);
                    _pendingMessages.get(notificationId).forEach(msg -> actor.tell(msg, self()));
                    _pendingMessages.removeAll(notificationId);
                    return actor;
                });
    }

    private String hashAlert(final AlertTrigger alert) {
        return alert.getArgs()
                .entrySet()
                .stream()
                .map(entry -> String.format("%s:%s", entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(";"))
                .replace('/', '.');
    }

    private void loadAlert() {
        final Optional<Alert> alertOptional = _alertRepository.get(_alertId, Organization.DEFAULT);
        if (alertOptional.isPresent()) {
            final Alert alert = alertOptional.get();
            if (!timers().isTimerActive("ALERT_REFRESH")) {
                timers().startPeriodicTimer("ALERT_REFRESH", RefreshAlert.getInstance(), Duration.apply(5, TimeUnit.MINUTES));
            }
            if (!alert.getPeriod().equals(_executePeriod)) {
                _executePeriod = alert.getPeriod();
                timers().startPeriodicTimer(
                        "ALERT_EXECUTE",
                        ExecuteAlert.getInstance(),
                        Duration.apply(_executePeriod.toStandardDuration().getMillis(), TimeUnit.MILLISECONDS));
            }
            if (!alert.getQuery().equals(_query)) {
                _query = alert.getQuery();
                try {
                    _parsedStatement = null;
                    _parsedStatement = parseQuery(_query);
                } catch (final ExecutionException ex) {
                    LOGGER.warn()
                            .setMessage("Invalid query in alert")
                            .addData("alertId", _alertId)
                            .addData("query", _query)
                            .log();
                }
            }
        } else {
            LOGGER.info()
                    .setMessage("Tried loading executor for alert that does not exist, shutting down executor")
                    .addData("alertId", _alertId)
                    .log();
            context().parent().tell(new ShardRegion.Passivate(PoisonPill.getInstance()), self());
        }
    }

    private MqlParser.StatementContext parseQuery(final String query) throws ExecutionException {
        final MqlLexer lexer = new MqlLexer(new ANTLRInputStream(query));
        final CommonTokenStream tokens = new CommonTokenStream(lexer);
        final MqlParser parser = new MqlParser(tokens);
        final CollectingErrorListener errorListener = new CollectingErrorListener();
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);

        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
        MqlParser.StatementContext statement;
        try {
            statement = parser.statement(); // STAGE 1
            // CHECKSTYLE.OFF: IllegalCatch - Translate any failure to bad input.
        } catch (final RuntimeException ex) {
            // CHECKSTYLE.ON: IllegalCatch
            tokens.reset(); // rewind input stream
            parser.reset();
            parser.getInterpreter().setPredictionMode(PredictionMode.LL);
            statement = parser.statement();  // STAGE 2
        }

        if (parser.getNumberOfSyntaxErrors() != 0) {
            throw new ExecutionException(errorListener.getErrors());
        }
        return statement;
    }

    @Override
    public String persistenceId() {
        return "alert-" + getSelf().path().name();
    }

    private UUID _alertId;
    private Period _executePeriod;
    private String _query;
    private MqlParser.StatementContext _parsedStatement;

    private final Provider<QueryRunner> _queryRunner;
    private final Injector _injector;
    private final AlertRepository _alertRepository;
    private final BiMap<String, ActorRef> _notificationActors = HashBiMap.create();
    private final Set<ActorRef> _shuttingDownNotifications = Sets.newHashSet();
    private final Multimap<String, Object> _pendingMessages = HashMultimap.create();

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertExecutor.class);

    /**
     * Message class to instantiate an alert.
     */
    public static final class InstantiateAlert {
        /**
         * Public constructor.
         *
         * @param alertId the id of the alert
         */
        public InstantiateAlert(final UUID alertId) {
            _alertId = alertId;
        }

        public UUID getAlertId() {
            return _alertId;
        }

        private final UUID _alertId;
    }

    /**
     * Message class to execute the alert check.
     */
    public static final class ExecuteAlert {
        /**
         * Get the singleton instance.
         *
         * @return the singleton instance
         */
        public static ExecuteAlert getInstance() {
            return INSTANCE;
        }

        private ExecuteAlert() { }

        private static final ExecuteAlert INSTANCE = new ExecuteAlert();
    }

    /**
     * Message class to initiate a refresh of the alert.
     */
    public static final class RefreshAlert {
        /**
         * Get the singleton instance.
         *
         * @return the singleton instance
         */
        public static RefreshAlert getInstance() {
            return INSTANCE;
        }

        private RefreshAlert() { }

        private static final RefreshAlert INSTANCE = new RefreshAlert();
    }

    /**
     * Message envelope to facilitate sending messages without ids to sharded alert actors.
     */
    public static final class SendTo {
        /**
         * Public constructor.
         *
         * @param alertId the alert id for the actor to send to
         * @param payload wrapped payload
         */
        public SendTo(final UUID alertId, final Object payload) {
            _alertId = alertId;
            _payload = payload;
        }

        public UUID getAlertId() {
            return _alertId;
        }

        public Object getPayload() {
            return _payload;
        }

        private final UUID _alertId;
        private final Object _payload;
    }
}
