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
package models.view;

import java.util.List;

/**
 * Represents a group of notification entries.
 *
 * @author Brandon Arp (brandon dot arp at smartsheet dot com)
 */
public class NotificationGroup {
    public String getId() {
        return _id;
    }

    public void setId(final String id) {
        _id = id;
    }

    public String getName() {
        return _name;
    }

    public void setName(final String name) {
        _name = name;
    }

    public List<NotificationEntry> getEntries() {
        return _entries;
    }

    public void setEntries(final List<NotificationEntry> entries) {
        _entries = entries;
    }

    private String _id;
    private String _name;
    private List<NotificationEntry> _entries;
}
