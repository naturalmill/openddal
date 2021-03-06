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
package com.openddal.dbobject.table;

import java.util.ArrayList;
import java.util.HashSet;

import com.openddal.command.dml.Select;
import com.openddal.command.expression.Comparison;
import com.openddal.command.expression.ConditionAndOr;
import com.openddal.command.expression.Expression;
import com.openddal.command.expression.ExpressionColumn;
import com.openddal.command.expression.ExpressionVisitor;
import com.openddal.dbobject.index.IndexCondition;
import com.openddal.engine.Session;
import com.openddal.engine.SysProperties;
import com.openddal.excutor.cursor.SearchCursor;
import com.openddal.message.DbException;
import com.openddal.result.Row;
import com.openddal.result.SearchRow;
import com.openddal.util.New;
import com.openddal.value.Value;
import com.openddal.value.ValueLong;
import com.openddal.value.ValueNull;

/**
 * A table filter represents a table that is used in a query. There is one such
 * object whenever a table (or view) is used in a query. For example the
 * following query has 2 table filters: SELECT * FROM TEST T1, TEST T2.
 */
public class TableFilter implements ColumnResolver {

    private static final int BEFORE_FIRST = 0, FOUND = 1, AFTER_LAST = 2,
            NULL_ROW = 3;
    private final Table table;
    private final Select select;
    /**
     * The filter used to walk through the index.
     */
    private final SearchCursor cursor;
    /**
     * The index conditions used for direct index lookup (start or end).
     */
    private final ArrayList<IndexCondition> indexConditions = New.arrayList();
    private final int hashCode;
    /**
     * Whether this is a direct or indirect (nested) outer join
     */
    protected boolean joinOuterIndirect;
    private Session session;
    private String alias;
    private int scanCount;
    private boolean evaluatable;
    /**
     * Indicates that this filter is used in the plan.
     */
    private boolean used;
    /**
     * Additional conditions that can't be used for index lookup, but for row
     * filter for this table (ID=ID, NAME LIKE '%X%')
     */
    private Expression filterCondition;
    /**
     * The complete join condition.
     */
    private Expression joinCondition;
    private SearchRow currentSearchRow;
    private Row current;
    private int state;
    /**
     * The joined table (if there is one).
     */
    private TableFilter join;
    /**
     * Whether this is an outer join.
     */
    private boolean joinOuter;
    /**
     * The nested joined table (if there is one).
     */
    private TableFilter nestedJoin;
    private ArrayList<Column> naturalJoinColumns;
    private boolean foundOne;
    private Expression fullCondition;
    private Column[] searchColumns;

    /**
     * Create a new table filter object.
     *
     * @param session       the session
     * @param table         the table from where to read data
     * @param alias         the alias name
     * @param rightsChecked true if rights are already checked
     * @param select        the select statement
     */
    public TableFilter(Session session, Table table, String alias,
                       boolean rightsChecked, Select select) {
        this.session = session;
        this.table = table;
        this.alias = alias;
        this.select = select;
        this.cursor = new SearchCursor(this);
        hashCode = session.nextObjectId();
    }

    @Override
    public Select getSelect() {
        return select;
    }

    public Table getTable() {
        return table;
    }

    /**
     * Lock the table. This will also lock joined tables.
     *
     * @param s                   the session
     * @param exclusive           true if an exclusive lock is required
     * @param forceLockEvenInMvcc lock even in the MVCC mode
     */
    public void lock(Session s, boolean exclusive, boolean forceLockEvenInMvcc) {
        //table.lock(s, exclusive, forceLockEvenInMvcc);
        if (join != null) {
            join.lock(s, exclusive, forceLockEvenInMvcc);
        }
    }

    /**
     * Get the best plan item (index, cost) to use use for the current join
     * order.
     *
     * @param s the session
     * @param filters all joined table filters
     * @param filter the current table filter index
     * @return the best plan item
     */
    public PlanItem getBestPlanItem(Session s, TableFilter[] filters, int filter) {
        int len = table.getColumns().length;
        int[] masks = new int[len];
        for (IndexCondition condition : indexConditions) {
            if (condition.isEvaluatable()) {
                if (condition.isAlwaysFalse()) {
                    masks = null;
                    break;
                }
                int id = condition.getColumn().getColumnId();
                if (id >= 0) {
                    masks[id] |= condition.getMask(indexConditions);
                }
            }
        }
        PlanItem item = table.getBestPlanItem(s, masks, filters, filter);
        if (nestedJoin != null) {
            setEvaluatable(nestedJoin);
            item.setNestedJoinPlan(nestedJoin.getBestPlanItem(s, filters, filter));
            item.cost += item.cost * item.getNestedJoinPlan().cost;
        }
        if (join != null) {
            setEvaluatable(join);
            do {
                filter++;
            } while (filters[filter] != join);
            item.setJoinPlan(join.getBestPlanItem(s, filters, filter));
            item.cost += item.cost * item.getJoinPlan().cost;
        }
        return item;
    }

    private void setEvaluatable(TableFilter join) {
        if (session.getDatabase().getSettings().nestedJoins) {
            setEvaluatable(true);
            return;
        }
        // this table filter is now evaluatable - in all sub-joins
        do {
            Expression e = join.getJoinCondition();
            if (e != null) {
                e.setEvaluatable(this, true);
            }
            TableFilter n = join.getNestedJoin();
            if (n != null) {
                setEvaluatable(n);
            }
            join = join.getJoin();
        } while (join != null);
    }

    /**
     * Set what plan item (index, cost) to use use.
     *
     * @param item the plan item
     */
    public void setPlanItem(PlanItem item) {
        if (item == null) {
            // invalid plan, most likely because a column wasn't found
            // this will result in an exception later on
            return;
        }
        //setIndex(item.getIndex());
        if (nestedJoin != null) {
            if (item.getNestedJoinPlan() != null) {
                nestedJoin.setPlanItem(item.getNestedJoinPlan());
            }
        }
        if (join != null) {
            if (item.getJoinPlan() != null) {
                join.setPlanItem(item.getJoinPlan());
            }
        }
    }

    /**
     * Prepare reading rows. This method will remove all index conditions that
     * can not be used, and optimize the conditions.
     */
    public void prepare() {
        // forget all unused index conditions
        // the indexConditions list may be modified here
        /*
        for (int i = 0; i < indexConditions.size(); i++) {
            IndexCondition condition = indexConditions.get(i);
            if (!condition.isAlwaysFalse()) {
                Column col = condition.getColumn();
                if (col.getColumnId() >= 0) {
                    if (index.getColumnIndex(col) < 0) {
                        indexConditions.remove(i);
                        i--;
                    }
                }
            }
        }
        */
        HashSet<Column> columns = New.hashSet();
        select.isEverything(ExpressionVisitor.getColumnsVisitor(columns));
        ArrayList<Column> selected = New.arrayList(10);
        for (Column column : columns) {
            if(table == column.getTable()) {
                selected.add(column);
            }
        }
        searchColumns = selected.toArray(new Column[selected.size()]);
        
        if (nestedJoin != null) {
            if (SysProperties.CHECK && nestedJoin == this) {
                DbException.throwInternalError("self join");
            }
            nestedJoin.prepare();
        }
        if (join != null) {
            if (SysProperties.CHECK && join == this) {
                DbException.throwInternalError("self join");
            }
            join.prepare();
        }
        if (filterCondition != null) {
            filterCondition = filterCondition.optimize(session);
        }
        if (joinCondition != null) {
            joinCondition = joinCondition.optimize(session);
        }
    }

    /**
     * Start the query. This will reset the scan counts.
     *
     * @param s the session
     */
    public void startQuery(Session s) {
        this.session = s;
        scanCount = 0;
        if (nestedJoin != null) {
            nestedJoin.startQuery(s);
        }
        if (join != null) {
            join.startQuery(s);
        }
    }

    /**
     * Reset to the current position.
     */
    public void reset() {
        if (nestedJoin != null) {
            nestedJoin.reset();
        }
        if (join != null) {
            join.reset();
        }
        state = BEFORE_FIRST;
        foundOne = false;
    }

    /**
     * Check if there are more rows to read.
     *
     * @return true if there are
     */
    public boolean next() {
        if (state == AFTER_LAST) {
            return false;
        } else if (state == BEFORE_FIRST) {
            cursor.query();
            if (!cursor.isAlwaysFalse()) {
                if (nestedJoin != null) {
                    nestedJoin.reset();
                }
                if (join != null) {
                    join.reset();
                }
            }
        } else {
            // state == FOUND || NULL_ROW
            // the last row was ok - try next row of the join
            if (join != null && join.next()) {
                return true;
            }
        }
        while (true) {
            // go to the next row
            if (state == NULL_ROW) {
                break;
            }
            if (cursor.isAlwaysFalse()) {
                state = AFTER_LAST;
            } else if (nestedJoin != null) {
                if (state == BEFORE_FIRST) {
                    state = FOUND;
                }
            } else {
                if ((++scanCount & 4095) == 0) {
                    checkTimeout();
                }
                if (cursor.next()) {
                    currentSearchRow = cursor.getSearchRow();
                    current = null;
                    state = FOUND;
                } else {
                    state = AFTER_LAST;
                }
            }
            if (nestedJoin != null && state == FOUND) {
                if (!nestedJoin.next()) {
                    state = AFTER_LAST;
                    if (joinOuter && !foundOne) {
                        // possibly null row
                    } else {
                        continue;
                    }
                }
            }
            // if no more rows found, try the null row (for outer joins only)
            if (state == AFTER_LAST) {
                if (joinOuter && !foundOne) {
                    setNullRow();
                } else {
                    break;
                }
            }
            if (!isOk(filterCondition)) {
                continue;
            }
            boolean joinConditionOk = isOk(joinCondition);
            if (state == FOUND) {
                if (joinConditionOk) {
                    foundOne = true;
                } else {
                    continue;
                }
            }
            if (join != null) {
                join.reset();
                if (!join.next()) {
                    continue;
                }
            }
            // check if it's ok
            if (state == NULL_ROW || joinConditionOk) {
                return true;
            }
        }
        state = AFTER_LAST;
        return false;
    }

    /**
     * Set the state of this and all nested tables to the NULL row.
     */
    protected void setNullRow() {
        state = NULL_ROW;
        current = table.getNullRow();
        currentSearchRow = current;
        if (nestedJoin != null) {
            nestedJoin.visit(new TableFilterVisitor() {
                @Override
                public void accept(TableFilter f) {
                    f.setNullRow();
                }
            });
        }
    }

    private void checkTimeout() {
        session.checkCanceled();
        // System.out.println(this.alias+ " " + table.getName() + ": " +
        // scanCount);
    }

    private boolean isOk(Expression condition) {
        if (condition == null) {
            return true;
        }
        return Boolean.TRUE.equals(condition.getBooleanValue(session));
    }

    /**
     * Get the current row.
     *
     * @return the current row, or null
     */
    public Row get() {
        if (current == null && currentSearchRow != null) {
            current = cursor.get();
        }
        return current;
    }

    /**
     * Set the current row.
     *
     * @param current the current row
     */
    public void set(Row current) {
        // this is currently only used so that check constraints work - to set
        // the current (new) row
        this.current = current;
        this.currentSearchRow = current;
    }

    /**
     * Get the table alias name. If no alias is specified, the table name is
     * returned.
     *
     * @return the alias name
     */
    @Override
    public String getTableAlias() {
        if (alias != null) {
            return alias;
        }
        return table.getName();
    }

    public void addIndexCondition(IndexCondition condition) {
        indexConditions.add(condition);
    }


    public void addFilterCondition(Expression condition, boolean isJoin) {
        if (isJoin) {
            if (joinCondition == null) {
                joinCondition = condition;
            } else {
                joinCondition = new ConditionAndOr(ConditionAndOr.AND,
                        joinCondition, condition);
            }
        } else {
            if (filterCondition == null) {
                filterCondition = condition;
            } else {
                filterCondition = new ConditionAndOr(ConditionAndOr.AND,
                        filterCondition, condition);
            }
        }
    }


    public void addJoin(TableFilter filter, boolean outer, boolean nested,
                        final Expression on) {
        if (on != null) {
            on.mapColumns(this, 0);
            if (session.getDatabase().getSettings().nestedJoins) {
                visit(new TableFilterVisitor() {
                    @Override
                    public void accept(TableFilter f) {
                        on.mapColumns(f, 0);
                    }
                });
                filter.visit(new TableFilterVisitor() {
                    @Override
                    public void accept(TableFilter f) {
                        on.mapColumns(f, 0);
                    }
                });
            }
        }
        if (nested && session.getDatabase().getSettings().nestedJoins) {
            if (nestedJoin != null) {
                throw DbException.throwInternalError();
            }
            nestedJoin = filter;
            filter.joinOuter = outer;
            if (outer) {
                visit(new TableFilterVisitor() {
                    @Override
                    public void accept(TableFilter f) {
                        f.joinOuterIndirect = true;
                    }
                });
            }
            if (on != null) {
                filter.mapAndAddFilter(on);
            }
        } else {
            if (join == null) {
                join = filter;
                filter.joinOuter = outer;
                if (session.getDatabase().getSettings().nestedJoins) {
                    if (outer) {
                        filter.visit(new TableFilterVisitor() {
                            @Override
                            public void accept(TableFilter f) {
                                f.joinOuterIndirect = true;
                            }
                        });
                    }
                } else {
                    if (outer) {
                        // convert all inner joins on the right hand side to
                        // outer joins
                        TableFilter f = filter.join;
                        while (f != null) {
                            f.joinOuter = true;
                            f = f.join;
                        }
                    }
                }
                if (on != null) {
                    filter.mapAndAddFilter(on);
                }
            } else {
                join.addJoin(filter, outer, nested, on);
            }
        }
    }

    /**
     * Map the columns and add the join condition.
     *
     * @param on the condition
     */
    public void mapAndAddFilter(Expression on) {
        on.mapColumns(this, 0);
        addFilterCondition(on, true);
        on.createIndexConditions(session, this);
        if (nestedJoin != null) {
            on.mapColumns(nestedJoin, 0);
            on.createIndexConditions(session, nestedJoin);
        }
        if (join != null) {
            join.mapAndAddFilter(on);
        }
    }

    public TableFilter getJoin() {
        return join;
    }

    /**
     * Whether this is an outer joined table.
     *
     * @return true if it is
     */
    public boolean isJoinOuter() {
        return joinOuter;
    }

    /**
     * Whether this is indirectly an outer joined table (nested within an inner
     * join).
     *
     * @return true if it is
     */
    public boolean isJoinOuterIndirect() {
        return joinOuterIndirect;
    }

    /**
     * Remove all index conditions that are not used by the current index.
     */
    void removeUnusableIndexConditions() {
        // the indexConditions list may be modified here
        for (int i = 0; i < indexConditions.size(); i++) {
            IndexCondition cond = indexConditions.get(i);
            if (!cond.isEvaluatable()) {
                indexConditions.remove(i--);
            }
        }
    }

    public boolean isUsed() {
        return used;
    }

    public void setUsed(boolean used) {
        this.used = used;
    }

    /**
     * Remove the joined table
     */
    public void removeJoin() {
        this.join = null;
    }

    public Expression getJoinCondition() {
        return joinCondition;
    }

    /**
     * Remove the join condition.
     */
    public void removeJoinCondition() {
        this.joinCondition = null;
    }

    public Expression getFilterCondition() {
        return filterCondition;
    }

    /**
     * Remove the filter condition.
     */
    public void removeFilterCondition() {
        this.filterCondition = null;
    }

    public void setFullCondition(Expression condition) {
        this.fullCondition = condition;
        if (join != null) {
            join.setFullCondition(condition);
        }
    }

    /**
     * Optimize the full condition. This will add the full condition to the
     * filter condition.
     *
     * @param fromOuterJoin if this method was called from an outer joined table
     */
    void optimizeFullCondition(boolean fromOuterJoin) {
        if (fullCondition != null) {
            fullCondition.addFilterConditions(this, fromOuterJoin || joinOuter);
            if (nestedJoin != null) {
                nestedJoin.optimizeFullCondition(fromOuterJoin || joinOuter);
            }
            if (join != null) {
                join.optimizeFullCondition(fromOuterJoin || joinOuter);
            }
        }
    }

    /**
     * Update the filter and join conditions of this and all joined tables with
     * the information that the given table filter and all nested filter can now
     * return rows or not.
     *
     * @param filter the table filter
     * @param b      the new flag
     */
    public void setEvaluatable(TableFilter filter, boolean b) {
        filter.setEvaluatable(b);
        if (filterCondition != null) {
            filterCondition.setEvaluatable(filter, b);
        }
        if (joinCondition != null) {
            joinCondition.setEvaluatable(filter, b);
        }
        if (nestedJoin != null) {
            // don't enable / disable the nested join filters
            // if enabling a filter in a joined filter
            if (this == filter) {
                nestedJoin.setEvaluatable(nestedJoin, b);
            }
        }
        if (join != null) {
            join.setEvaluatable(filter, b);
        }
    }

    @Override
    public String getSchemaName() {
        return table.getSchema().getName();
    }

    @Override
    public Column[] getColumns() {
        return table.getColumns();
    }

    public Column[] getSearchColumns() {
        if(searchColumns == null || searchColumns.length == 0) {
            return table.getColumns();
        }
        return searchColumns;
    }

    /**
     * Get the system columns that this table understands. This is used for
     * compatibility with other databases. The columns are only returned if the
     * current mode supports system columns.
     *
     * @return the system columns
     */
    @Override
    public Column[] getSystemColumns() {
        if (!session.getDatabase().getMode().systemColumns) {
            return null;
        }
        Column[] sys = new Column[3];
        sys[0] = new Column("oid", Value.INT);
        sys[0].setTable(table, 0);
        sys[1] = new Column("ctid", Value.STRING);
        sys[1].setTable(table, 0);
        sys[2] = new Column("CTID", Value.STRING);
        sys[2].setTable(table, 0);
        return sys;
    }

    @Override
    public Column getRowIdColumn() {
        if (session.getDatabase().getSettings().rowId) {
            return table.getRowIdColumn();
        }
        return null;
    }

    @Override
    public Value getValue(Column column) {
        if (currentSearchRow == null) {
            return null;
        }
        int columnId = column.getColumnId();
        if (columnId == -1) {
            return ValueLong.get(currentSearchRow.getKey());
        }
        if (current == null) {
            Value v = currentSearchRow.getValue(columnId);
            if (v != null) {
                return v;
            }
            current = cursor.get();
            if (current == null) {
                return ValueNull.INSTANCE;
            }
        }
        return current.getValue(columnId);
    }

    @Override
    public TableFilter getTableFilter() {
        return this;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    @Override
    public Expression optimize(ExpressionColumn expressionColumn, Column column) {
        return expressionColumn;
    }

    @Override
    public String toString() {
        return alias != null ? alias : table.toString();
    }

    /**
     * Add a column to the natural join key column list.
     *
     * @param c the column to add
     */
    public void addNaturalJoinColumn(Column c) {
        if (naturalJoinColumns == null) {
            naturalJoinColumns = New.arrayList();
        }
        naturalJoinColumns.add(c);
    }

    /**
     * Check if the given column is a natural join column.
     *
     * @param c the column to check
     * @return true if this is a joined natural join column
     */
    public boolean isNaturalJoinColumn(Column c) {
        return naturalJoinColumns != null && naturalJoinColumns.contains(c);
    }

    /**
     * test the table is table mata
     *
     * @return
     */
    public boolean isFromTableMate() {
        return table instanceof TableMate;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    /**
     * Are there any index conditions that involve IN(...).
     *
     * @return whether there are IN(...) comparisons
     */
    public boolean hasInComparisons() {
        for (IndexCondition cond : indexConditions) {
            int compareType = cond.getCompareType();
            if (compareType == Comparison.IN_QUERY || compareType == Comparison.IN_LIST) {
                return true;
            }
        }
        return false;
    }

    /**
     * Add the current row to the array, if there is a current row.
     *
     * @param rows the rows to lock
     */
    public void lockRowAdd(ArrayList<Row> rows) {
        if (state == FOUND) {
            rows.add(get());
        }
    }

    /**
     * Lock the given rows.
     *
     * @param forUpdateRows the rows to lock
     */
    public void lockRows(ArrayList<Row> forUpdateRows) {
        //for (Row row : forUpdateRows) {
        //Row newRow = row.getCopy();
        //table.removeRow(session, row);
        //table.addRow(session, newRow);
        //}
    }

    public TableFilter getNestedJoin() {
        return nestedJoin;
    }

    public ArrayList<IndexCondition> getIndexConditions() {
        return indexConditions;
    }

    /**
     * Visit this and all joined or nested table filters.
     *
     * @param visitor the visitor
     */
    public void visit(TableFilterVisitor visitor) {
        TableFilter f = this;
        do {
            visitor.accept(f);
            TableFilter n = f.nestedJoin;
            if (n != null) {
                n.visit(visitor);
            }
            f = f.join;
        } while (f != null);
    }

    public boolean isEvaluatable() {
        return evaluatable;
    }

    public void setEvaluatable(boolean evaluatable) {
        this.evaluatable = evaluatable;
    }

    public Session getSession() {
        return session;
    }

    /**
     * Set the session of this table filter.
     *
     * @param session the new session
     */
    void setSession(Session session) {
        this.session = session;
    }

    /**
     * A visitor for table filters.
     */
    public interface TableFilterVisitor {

        /**
         * This method is called for each nested or joined table filter.
         *
         * @param f the filter
         */
        void accept(TableFilter f);
    }

}
