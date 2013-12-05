/*
 * #%L
 * BroadleafCommerce Workflow
 * %%
 * Copyright (C) 2009 - 2013 Broadleaf Commerce
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.broadleafcommerce.common.util;

import java.util.Collection;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.persistence.EntityManager;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.broadleafcommerce.common.exception.ExceptionHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * @author Jeff Fischer
 */
@Component("blStreamingTransactionCapableUtil")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class StreamingTransactionCapableUtil implements StreamingTransactionCapable {

    private static final Log LOG = LogFactory.getLog(StreamingTransactionCapableUtil.class);

    @Resource(name = "blTransactionManager")
    protected PlatformTransactionManager transactionManager;

    protected EntityManager em;

    @Value("${streaming.transaction.item.page.size}")
    protected int pageSize;

    @PostConstruct
    public void init() {
        if (transactionManager instanceof JpaTransactionManager) {
            em = ((JpaTransactionManager) transactionManager).getEntityManagerFactory().createEntityManager();
        }
    }

    @Override
    public <G extends Throwable> void runStreamingTransactionalOperation(final StreamCapableTransactionalOperation
                                        streamOperation, Class<G> exceptionType) throws G {
        runStreamingTransactionalOperation(streamOperation, exceptionType, TransactionDefinition.PROPAGATION_REQUIRED, TransactionDefinition.ISOLATION_DEFAULT);
    }

    @Override
    public <G extends Throwable> void runStreamingTransactionalOperation(final StreamCapableTransactionalOperation
                                        streamOperation, Class<G> exceptionType, int transactionBehavior, int isolationLevel) throws G {
        //this should be a read operation, so doesn't need to be in a transaction
        final Long totalCount = streamOperation.retrieveTotalCount();
        final Holder holder = new Holder();
        holder.setVal(0);
        StreamCapableTransactionalOperation operation = new StreamCapableTransactionalOperationAdapter() {
            @Override
            public void execute() throws Throwable {
                Object[] items = streamOperation.retrievePage(holder.getVal(), pageSize);
                streamOperation.pagedExecute(items);
                if (((Collection) items[0]).size() == 0) {
                    holder.setVal(totalCount.intValue());
                } else {
                    holder.setVal(holder.getVal() + ((Collection) items[0]).size());
                }
            }
        };
        while (holder.getVal() < totalCount) {
            runTransactionalOperation(operation, exceptionType, transactionBehavior, isolationLevel);
            if (em != null) {
                //The idea behind using this class is that it will likely process a lot of records. As such, it is necessary
                //to clear the level 1 cache after each iteration so that we don't run out of heap
                em.clear();
            }
        }
    }

    @Override
    public <G extends Throwable> void runTransactionalOperation(StreamCapableTransactionalOperation operation,
                                        Class<G> exceptionType) throws G {
        runTransactionalOperation(operation, exceptionType, TransactionDefinition.PROPAGATION_REQUIRED, TransactionDefinition.ISOLATION_DEFAULT);
    }

    @Override
    public <G extends Throwable> void runTransactionalOperation(StreamCapableTransactionalOperation operation,
                                        Class<G> exceptionType, int transactionBehavior, int isolationLevel) throws G {
        TransactionStatus status = startTransaction(transactionBehavior, isolationLevel);
        boolean isError = false;
        try {
            operation.execute();
        } catch (Throwable e) {
            isError = true;
            ExceptionHelper.processException(exceptionType, RuntimeException.class, e);
        } finally {
            endTransaction(status, isError, exceptionType);
        }
    }

    @Override
    public <G extends Throwable> void runOptionalTransactionalOperation(StreamCapableTransactionalOperation operation,
                                        Class<G> exceptionType, boolean useTransaction) throws G {
        runOptionalTransactionalOperation(operation, exceptionType, useTransaction, TransactionDefinition.PROPAGATION_REQUIRED, TransactionDefinition.ISOLATION_DEFAULT);
    }

    @Override
    public <G extends Throwable> void runOptionalTransactionalOperation(StreamCapableTransactionalOperation operation,
                                        Class<G> exceptionType, boolean useTransaction, int transactionBehavior, int isolationLevel) throws G {
        TransactionStatus status = null;
        if (useTransaction) {
            status = startTransaction(transactionBehavior, isolationLevel);
        }
        boolean isError = false;
        try {
            operation.execute();
        } catch (Throwable e) {
            isError = true;
            ExceptionHelper.processException(exceptionType, RuntimeException.class, e);
        } finally {
            if (useTransaction) {
                endTransaction(status, isError, exceptionType);
            }
        }
    }

    @Override
    public int getPageSize() {
        return pageSize;
    }

    @Override
    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    @Override
    public PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }

    @Override
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
        init();
    }

    protected <G extends Throwable> void endTransaction(TransactionStatus status, boolean error, Class<G> exceptionType) throws G {
        try {
            TransactionUtils.finalizeTransaction(status, transactionManager, error);
        } catch (Throwable e) {
            ExceptionHelper.processException(exceptionType, RuntimeException.class, e);
        }
    }

    protected TransactionStatus startTransaction(int propagationBehavior, int isolationLevel) {
        TransactionStatus status;
        try {
            status = TransactionUtils.createTransaction(propagationBehavior, isolationLevel,
                    transactionManager, false);
        } catch (RuntimeException e) {
            LOG.error("Could not start transaction", e);
            throw e;
        }
        return status;
    }

    private class Holder {

        private int val;

        public int getVal() {
            return val;
        }

        public void setVal(int val) {
            this.val = val;
        }
    }
}
