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
package com.openddal.command.expression;

import com.openddal.dbobject.index.IndexCondition;
import com.openddal.dbobject.table.ColumnResolver;
import com.openddal.dbobject.table.TableFilter;
import com.openddal.engine.Session;
import com.openddal.message.DbException;
import com.openddal.util.StatementBuilder;
import com.openddal.value.Value;
import com.openddal.value.ValueBoolean;
import com.openddal.value.ValueNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Used for optimised IN(...) queries where the contents of the IN list are all
 * constant and of the same type.
 * <p>
 * Checking using a HashSet is has time complexity O(1), instead of O(n) for
 * checking using an array.
 */
public class ConditionInConstantSet extends Condition {

    private final ArrayList<Expression> valueList;
    private final TreeSet<Value> valueSet;
    private Expression left;
    private int queryLevel;

    /**
     * Create a new IN(..) condition.
     *
     * @param session   the session
     * @param left      the expression before IN
     * @param valueList the value list (at least two elements)
     */
    public ConditionInConstantSet(final Session session, Expression left,
                                  ArrayList<Expression> valueList) {
        this.left = left;
        this.valueList = valueList;
        this.valueSet = new TreeSet<Value>(new Comparator<Value>() {
            @Override
            public int compare(Value o1, Value o2) {
                return session.getDatabase().compare(o1, o2);
            }
        });
        int type = left.getType();
        for (Expression expression : valueList) {
            valueSet.add(expression.getValue(session).convertTo(type));
        }
    }

    @Override
    public Value getValue(Session session) {
        Value x = left.getValue(session);
        if (x == ValueNull.INSTANCE) {
            return x;
        }
        boolean result = valueSet.contains(x);
        if (!result) {
            boolean setHasNull = valueSet.contains(ValueNull.INSTANCE);
            if (setHasNull) {
                return ValueNull.INSTANCE;
            }
        }
        return ValueBoolean.get(result);
    }

    @Override
    public void mapColumns(ColumnResolver resolver, int level) {
        left.mapColumns(resolver, level);
        this.queryLevel = Math.max(level, this.queryLevel);
    }

    @Override
    public Expression optimize(Session session) {
        left = left.optimize(session);
        return this;
    }

    @Override
    public void createIndexConditions(Session session, TableFilter filter) {
        if (!(left instanceof ExpressionColumn)) {
            return;
        }
        ExpressionColumn l = (ExpressionColumn) left;
        if (filter != l.getTableFilter()) {
            return;
        }
        if (session.getDatabase().getSettings().optimizeInList) {
            filter.addIndexCondition(IndexCondition.getInList(l, valueList));
            return;
        }
    }

    @Override
    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        left.setEvaluatable(tableFilter, b);
    }

    @Override
    public String getSQL() {
        StatementBuilder buff = new StatementBuilder("(");
        buff.append(left.getSQL()).append(" IN(");
        for (Expression e : valueList) {
            buff.appendExceptFirst(", ");
            buff.append(e.getSQL());
        }
        return buff.append("))").toString();
    }

    @Override
    public void updateAggregate(Session session) {
        // nothing to do
    }

    @Override
    public boolean isEverything(ExpressionVisitor visitor) {
        if (!left.isEverything(visitor)) {
            return false;
        }
        switch (visitor.getType()) {
            case ExpressionVisitor.OPTIMIZABLE_MIN_MAX_COUNT_ALL:
            case ExpressionVisitor.DETERMINISTIC:
            case ExpressionVisitor.READONLY:
            case ExpressionVisitor.INDEPENDENT:
            case ExpressionVisitor.EVALUATABLE:
            case ExpressionVisitor.SET_MAX_DATA_MODIFICATION_ID:
            case ExpressionVisitor.NOT_FROM_RESOLVER:
            case ExpressionVisitor.GET_DEPENDENCIES:
            case ExpressionVisitor.QUERY_COMPARABLE:
            case ExpressionVisitor.GET_COLUMNS:
                return true;
            default:
                throw DbException.throwInternalError("type=" + visitor.getType());
        }
    }

    @Override
    public int getCost() {
        int cost = left.getCost();
        return cost;
    }

    /**
     * Add an additional element if possible. Example: given two conditions
     * A IN(1, 2) OR A=3, the constant 3 is added: A IN(1, 2, 3).
     *
     * @param session the session
     * @param other   the second condition
     * @return null if the condition was not added, or the new condition
     */
    Expression getAdditional(Session session, Comparison other) {
        Expression add = other.getIfEquals(left);
        if (add != null) {
            if (add.isConstant()) {
                valueList.add(add);
                valueSet.add(add.getValue(session).convertTo(left.getType()));
                return this;
            }
        }
        return null;
    }

    @Override
    public String exportParameters(TableFilter filter, List<Value> container) {
        StatementBuilder buff = new StatementBuilder("(");
        buff.append(left.exportParameters(filter, container)).append(" IN(");
        for (Expression e : valueList) {
            buff.appendExceptFirst(", ");
            buff.append(e.exportParameters(filter, container));
        }
        return buff.append("))").toString();
    }
}
