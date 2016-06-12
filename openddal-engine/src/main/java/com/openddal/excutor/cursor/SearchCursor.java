/*
 * Copyright 2014-2016 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.openddal.excutor.cursor;

import com.openddal.command.dml.Select;
import com.openddal.dbobject.table.TableFilter;
import com.openddal.excutor.ExecutionFramework;
import com.openddal.message.DbException;
import com.openddal.result.Row;
import com.openddal.result.SearchRow;

/**
 * @author <a href="mailto:jorgie.mail@gmail.com">jorgie li</a>
 */
public class SearchCursor extends ExecutionFramework<Select> implements Cursor {

    private final TableFilter tableFilter;
    private Cursor cursor;
    private boolean alwaysFalse;

    public SearchCursor(TableFilter tableFilter) {
        super(tableFilter.getSelect());
        this.tableFilter = tableFilter;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.openddal.excutor.ExecutionFramework#doPrepare()
     */
    @Override
    public void doPrepare() {

    }

    @Override
    public String doExplain() {
        return null;
    }

    /**
     * Re-evaluate the start and end values of the index search for rows.
     *
     * @param s the session
     * @param indexConditions the index conditions
     */
    public void doQuery() {
        if (!alwaysFalse) {

        }
    }

    /**
     * Check if the result is empty for sure.
     *
     * @return true if it is
     */
    public boolean isAlwaysFalse() {
        return alwaysFalse;
    }

    @Override
    public Row get() {
        if (cursor == null) {
            return null;
        }
        return cursor.get();
    }

    @Override
    public SearchRow getSearchRow() {
        return cursor.getSearchRow();
    }

    @Override
    public boolean next() {
        while (true) {
            if (cursor == null) {
                nextCursor();
                if (cursor == null) {
                    return false;
                }
            }
            if (cursor.next()) {
                return true;
            }
            cursor = null;
        }
    }

    private void nextCursor() {

    }

    @Override
    public boolean previous() {
        throw DbException.throwInternalError();
    }

}
