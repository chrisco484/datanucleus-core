/**********************************************************************
Copyright (c) 2012 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
   ...
**********************************************************************/
package org.datanucleus.store.schema.table;

import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.JdbcType;
import org.datanucleus.store.schema.naming.ColumnType;

/**
 * Interface representing a column in a table.
 */
public interface Column
{
    /**
     * Accessor for the table for this column.
     * @return The table
     */
    Table getTable();

    /**
     * Accessor for the metadata for the member (if for a member).
     * @return Member metadata
     */
    AbstractMemberMetaData getMemberMetaData();

    /**
     * Accessor for the identifier for this column (its "name).
     * @return The column identifier
     */
    String getIdentifier();

    boolean isPrimaryKey();

    ColumnType getColumnType();

    void setJdbcType(JdbcType jdbcType);

    JdbcType getJdbcType();

    void setTypeName(String type);

    String getTypeName();

    void setPosition(int pos);

    /**
     * Accessor for the position of this column in the table (if specified).
     * @return The position, or -1 if not specified
     */
    int getPosition();
}