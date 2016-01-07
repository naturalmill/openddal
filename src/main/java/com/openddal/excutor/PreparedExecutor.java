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
// Created on 2015年4月10日
// $Id$

package com.openddal.excutor;

import com.openddal.message.DbException;
import com.openddal.result.LocalResult;
import com.openddal.result.ResultTarget;

/**
 * @author <a href="mailto:jorgie.mail@gmail.com">jorgie li</a>
 */
public interface PreparedExecutor {
    /**
     * Execute the query.
     *
     * @param maxRows the maximum number of rows to return
     * @return the result set
     * @throws DbException if it is not a query
     */
    LocalResult executeQuery(int maxRows);

    /**
     * Execute the query, writing the result to the target result.
     *
     * @param maxRows the maximum number of rows to return
     * @param target  the target result (null will return the result)
     * @return the result set (if the target is not set).
     */
    LocalResult executeQuery(int maxRows, ResultTarget target);

    /**
     * Execute the statement.
     *
     * @return the update count
     * @throws DbException if it is a query
     */
    int executeUpdate();

    /**
     * kill a currently running PreparedExecutor.
     * This operation will cancel all opened JDBC statements
     * and close all opened JDBC connections
     */
    void kill();
}
