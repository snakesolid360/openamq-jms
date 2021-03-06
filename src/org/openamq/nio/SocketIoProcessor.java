/*
 *   @(#) $Id: SocketIoProcessor.java 372449 2006-01-26 05:24:58Z trustin $
 *
 *   Copyright 2004 The Apache Software Foundation
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.openamq.nio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.mina.common.ByteBuffer;
import org.apache.mina.common.ExceptionMonitor;
import org.apache.mina.common.IdleStatus;
import org.apache.mina.common.IoFilter.WriteRequest;
import org.apache.mina.common.WriteTimeoutException;
import org.apache.mina.util.Queue;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * Performs all I/O operations for sockets which is connected or bound.
 * This class is used by MINA internally.
 *
 * @author The Apache Directory Project (dev@directory.apache.org)
 * @version $Rev: 372449 $, $Date: 2006-01-26 05:24:58 +0000 (Thu, 26 Jan 2006) $,
 */
class SocketIoProcessor
{
    private static final Logger _logger = LoggerFactory.getLogger(SocketIoProcessor.class);

    private static final String PROCESSORS_PROPERTY = "mina.socket.processors";
    private static final String THREAD_PREFIX = "SocketIoProcessor-";
    private static final int DEFAULT_PROCESSORS = 1;
    private static final int PROCESSOR_COUNT;
    private static final SocketIoProcessor[] PROCESSORS;

    private static int nextId;

    static
    {
        PROCESSOR_COUNT = configureProcessorCount();
        PROCESSORS = createProcessors();
    }

    /**
     * Returns the {@link SocketIoProcessor} to be used for a newly
     * created session
     *
     * @return The processor to be employed
     */
    static synchronized SocketIoProcessor getInstance()
    {
        SocketIoProcessor processor = PROCESSORS[nextId ++];
        nextId %= PROCESSOR_COUNT;
        return processor;
    }

    private final String threadName;
    private Selector selector;

    private final Queue newSessions = new Queue();
    private final Queue removingSessions = new Queue();
    private final Queue flushingSessions = new Queue();
    private final Queue trafficControllingSessions = new Queue();

    private Worker worker;
    private long lastIdleCheckTime = System.currentTimeMillis();

    private SocketIoProcessor(String threadName)
    {
        this.threadName = threadName;
    }

    void addNew(SocketSessionImpl session) throws IOException
    {
        synchronized (this)
        {
            synchronized (newSessions)
            {
                newSessions.push(session);
            }
            startupWorker();
        }

        selector.wakeup();
    }

    void remove(SocketSessionImpl session) throws IOException
    {
        scheduleRemove(session);
        startupWorker();
        selector.wakeup();
    }

    private synchronized void startupWorker() throws IOException
    {
        if (worker == null)
        {
            selector = Selector.open();
            worker = new Worker();
            worker.start();
        }
    }

    void flush(SocketSessionImpl session)
    {
        scheduleFlush(session);
        Selector selector = this.selector;
        if (selector != null)
        {
            selector.wakeup();
        }
    }

    void updateTrafficMask(SocketSessionImpl session)
    {
        scheduleTrafficControl(session);
        Selector selector = this.selector;
        if (selector != null)
        {
            selector.wakeup();
        }
    }

    private void scheduleRemove(SocketSessionImpl session)
    {
        synchronized (removingSessions)
        {
            removingSessions.push(session);
        }
    }

    private void scheduleFlush(SocketSessionImpl session)
    {
        synchronized (flushingSessions)
        {
            flushingSessions.push(session);
        }
    }

    private void scheduleTrafficControl(SocketSessionImpl session)
    {
        synchronized (trafficControllingSessions)
        {
            trafficControllingSessions.push(session);
        }
    }

    private void doAddNew()
    {
        if (newSessions.isEmpty())
        {
            return;
        }

        SocketSessionImpl session;

        for (; ;)
        {
            synchronized (newSessions)
            {
                session = (SocketSessionImpl) newSessions.pop();
            }

            if (session == null)
            {
                break;
            }

            SocketChannel ch = session.getChannel();
            boolean registered;

            try
            {
                ch.configureBlocking(false);
                session.setSelectionKey(ch.register(selector,
                                                    SelectionKey.OP_READ,
                                                    session));
                registered = true;
            }
            catch (IOException e)
            {
                session.getManagedSessions().remove(session);
                registered = false;
                ((SocketFilterChain) session.getFilterChain()).exceptionCaught(session, e);
            }

            if (registered)
            {
                ((SocketFilterChain) session.getFilterChain()).sessionOpened(session);
            }
        }
    }

    private void doRemove()
    {
        if (removingSessions.isEmpty())
        {
            return;
        }

        for (; ;)
        {
            SocketSessionImpl session;

            synchronized (removingSessions)
            {
                session = (SocketSessionImpl) removingSessions.pop();
            }

            if (session == null)
            {
                break;
            }

            SocketChannel ch = session.getChannel();
            SelectionKey key = session.getSelectionKey();
            // Retry later if session is not yet fully initialized.
            // (In case that Session.close() is called before addSession() is processed)
            if (key == null)
            {
                scheduleRemove(session);
                break;
            }
            // skip if channel is already closed
            if (!key.isValid())
            {
                continue;
            }

            try
            {
                key.cancel();
                ch.close();
            }
            catch (IOException e)
            {
                ((SocketFilterChain) session.getFilterChain()).exceptionCaught(session, e);
            }
            finally
            {
                releaseWriteBuffers(session);
                session.getManagedSessions().remove(session);

                ((SocketFilterChain) session.getFilterChain()).sessionClosed(session);
                session.getCloseFuture().setClosed();
            }
        }
    }

    private void process(Set selectedKeys)
    {
        Iterator it = selectedKeys.iterator();

        while (it.hasNext())
        {
            SelectionKey key = (SelectionKey) it.next();
            SocketSessionImpl session = (SocketSessionImpl) key.attachment();

            if (key.isReadable() && session.getTrafficMask().isReadable())
            {
                read(session);
            }

            if (key.isWritable() && session.getTrafficMask().isWritable())
            {
                scheduleFlush(session);
            }
        }

        selectedKeys.clear();
    }

    private void read(SocketSessionImpl session)
    {
        ByteBuffer buf = ByteBuffer.allocate(session.getReadBufferSize());
        SocketChannel ch = session.getChannel();

        try
        {
            int readBytes = 0;
            int ret;

            buf.clear();

            try
            {
                while ((ret = ch.read(buf.buf())) > 0)
                {
                    readBytes += ret;
                }
            }
            finally
            {
                buf.flip();
            }

            session.increaseReadBytes(readBytes);

            if (readBytes > 0)
            {
                /*ByteBuffer newBuf = ByteBuffer.allocate(readBytes);
                newBuf.put(buf);
                newBuf.flip();*/
                //((SocketFilterChain) session.getFilterChain()).messageReceived(session, newBuf);
                ((SocketFilterChain) session.getFilterChain()).messageReceived(session, buf);
            }
            if (ret < 0)
            {
                scheduleRemove(session);
            }
        }
        catch (Throwable e)
        {
            if (e instanceof IOException)
            {
                scheduleRemove(session);
            }
            buf.release();
            ((SocketFilterChain) session.getFilterChain()).exceptionCaught(session, e);
        }
        /*finally
        {
            buf.release();
        } */
    }

    private void notifyIdleness()
    {
        // process idle sessions
        long currentTime = System.currentTimeMillis();
        if ((currentTime - lastIdleCheckTime) >= 1000)
        {
            lastIdleCheckTime = currentTime;
            Set keys = selector.keys();
            if (keys != null)
            {
                for (Iterator it = keys.iterator(); it.hasNext();)
                {
                    SelectionKey key = (SelectionKey) it.next();
                    SocketSessionImpl session = (SocketSessionImpl) key.attachment();
                    notifyIdleness(session, currentTime);
                }
            }
        }
    }

    private void notifyIdleness(SocketSessionImpl session, long currentTime)
    {
        notifyIdleness0(
                session, currentTime,
                session.getIdleTimeInMillis(IdleStatus.BOTH_IDLE),
                IdleStatus.BOTH_IDLE,
                Math.max(session.getLastIoTime(), session.getLastIdleTime(IdleStatus.BOTH_IDLE)));
        notifyIdleness0(
                session, currentTime,
                session.getIdleTimeInMillis(IdleStatus.READER_IDLE),
                IdleStatus.READER_IDLE,
                Math.max(session.getLastReadTime(), session.getLastIdleTime(IdleStatus.READER_IDLE)));
        notifyIdleness0(
                session, currentTime,
                session.getIdleTimeInMillis(IdleStatus.WRITER_IDLE),
                IdleStatus.WRITER_IDLE,
                Math.max(session.getLastWriteTime(), session.getLastIdleTime(IdleStatus.WRITER_IDLE)));

        notifyWriteTimeout(session, currentTime, session
                .getWriteTimeoutInMillis(), session.getLastWriteTime());
    }

    private void notifyIdleness0(SocketSessionImpl session, long currentTime,
                                 long idleTime, IdleStatus status,
                                 long lastIoTime)
    {
        if (idleTime > 0 && lastIoTime != 0
                && (currentTime - lastIoTime) >= idleTime)
        {
            session.increaseIdleCount(status);
            ((SocketFilterChain) session.getFilterChain()).sessionIdle(session, status);
        }
    }

    private void notifyWriteTimeout(SocketSessionImpl session,
                                    long currentTime,
                                    long writeTimeout, long lastIoTime)
    {
        SelectionKey key = session.getSelectionKey();
        if (writeTimeout > 0
                && (currentTime - lastIoTime) >= writeTimeout
                && key != null && key.isValid()
                && (key.interestOps() & SelectionKey.OP_WRITE) != 0)
        {
            ((SocketFilterChain) session.getFilterChain()).exceptionCaught(session, new WriteTimeoutException());
        }
    }

    private void doFlush()
    {
        if (flushingSessions.size() == 0)
        {
            return;
        }

        for (; ;)
        {
            SocketSessionImpl session;

            synchronized (flushingSessions)
            {
                session = (SocketSessionImpl) flushingSessions.pop();
            }

            if (session == null)
            {
                break;
            }

            if (!session.isConnected())
            {
                releaseWriteBuffers(session);
                continue;
            }

            SelectionKey key = session.getSelectionKey();
            // Retry later if session is not yet fully initialized.
            // (In case that Session.write() is called before addSession() is processed)
            if (key == null)
            {
                scheduleFlush(session);
                break;
            }
            // skip if channel is already closed
            if (!key.isValid())
            {
                continue;
            }

            try
            {
                doFlush(session);
            }
            catch (IOException e)
            {
                scheduleRemove(session);
                ((SocketFilterChain) session.getFilterChain()).exceptionCaught(session, e);
            }
        }
    }

    private void releaseWriteBuffers(SocketSessionImpl session)
    {
        Queue writeRequestQueue = session.getWriteRequestQueue();
        WriteRequest req;

        while ((req = (WriteRequest) writeRequestQueue.pop()) != null)
        {
            try
            {
                ((ByteBuffer) req.getMessage()).release();
            }
            catch (IllegalStateException e)
            {
                ((SocketFilterChain) session.getFilterChain()).exceptionCaught(session, e);
            }
            finally
            {
                req.getFuture().setWritten(false);
            }
        }
    }

    private void doFlush(SocketSessionImpl session) throws IOException
    {
        // Clear OP_WRITE
        SelectionKey key = session.getSelectionKey();
        key.interestOps(key.interestOps() & (~SelectionKey.OP_WRITE));

        SocketChannel ch = session.getChannel();
        Queue writeRequestQueue = session.getWriteRequestQueue();

        WriteRequest req;
        for (; ;)
        {
            synchronized (writeRequestQueue)
            {
                req = (WriteRequest) writeRequestQueue.first();
            }

            if (req == null)
            {
                break;
            }

            ByteBuffer buf = (ByteBuffer) req.getMessage();
            if (buf.remaining() == 0)
            {
                synchronized (writeRequestQueue)
                {
                    writeRequestQueue.pop();
                }

                session.increaseWrittenWriteRequests();
                buf.reset();
                ((SocketFilterChain) session.getFilterChain()).messageSent(session, req);
                continue;
            }

            int writtenBytes = ch.write(buf.buf());
            if (writtenBytes > 0)
            {
                session.increaseWrittenBytes(writtenBytes);
            }

            if (buf.hasRemaining())
            {
                //_logger.info("Kernel buf full");
                // Kernel buffer is full
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                selector.wakeup();
                break;
            }
        }
    }

    private void doUpdateTrafficMask()
    {
        if (trafficControllingSessions.isEmpty())
        {
            return;
        }

        for (; ;)
        {
            SocketSessionImpl session;

            synchronized (trafficControllingSessions)
            {
                session = (SocketSessionImpl) trafficControllingSessions.pop();
            }

            if (session == null)
            {
                break;
            }

            SelectionKey key = session.getSelectionKey();
            // Retry later if session is not yet fully initialized.
            // (In case that Session.suspend??() or session.resume??() is
            // called before addSession() is processed)
            if (key == null)
            {
                scheduleTrafficControl(session);
                break;
            }
            // skip if channel is already closed
            if (!key.isValid())
            {
                continue;
            }

            // The normal is OP_READ and, if there are write requests in the
            // session's write queue, set OP_WRITE to trigger flushing.
            int ops = SelectionKey.OP_READ;
            Queue writeRequestQueue = session.getWriteRequestQueue();
            synchronized (writeRequestQueue)
            {
                if (!writeRequestQueue.isEmpty())
                {
                    ops |= SelectionKey.OP_WRITE;
                }
            }

            // Now mask the preferred ops with the mask of the current session
            int mask = session.getTrafficMask().getInterestOps();
            key.interestOps(ops & mask);
        }
    }

    /**
     * Configures the number of processors employed.
     * We first check for a system property "mina.IoProcessors". If this
     * property is present and can be interpreted as an integer value greater
     * or equal to 1, this value is used as the number of processors.
     * Otherwise a default of 1 processor is employed.
     *
     * @return The nubmer of processors to employ
     */
    private static int configureProcessorCount()
    {
        int processors = DEFAULT_PROCESSORS;
        String processorProperty = System.getProperty(PROCESSORS_PROPERTY);
        if (processorProperty != null)
        {
            try
            {
                processors = Integer.parseInt(processorProperty);
            }
            catch (NumberFormatException e)
            {
                ExceptionMonitor.getInstance().exceptionCaught(e);
            }
            processors = Math.max(processors, 1);

            System.setProperty(PROCESSORS_PROPERTY, String.valueOf(processors));
        }

        return processors;
    }

    private static SocketIoProcessor[] createProcessors()
    {
        SocketIoProcessor[] processors = new SocketIoProcessor[ PROCESSOR_COUNT ];
        for (int i = 0; i < PROCESSOR_COUNT; i ++)
        {
            processors[i] = new SocketIoProcessor(THREAD_PREFIX + i);
        }
        return processors;
    }

    private class WorkerFlusher implements Runnable
    {
        private volatile boolean _shutdown = false;

        private volatile boolean _sleep = false;

        private final Object _lock = new Object();

        public void run()
        {
            while (!_shutdown)
            {
                doFlush();
                try
                {
                    sleep();
                }
                catch (InterruptedException e)
                {
                    // IGNORE
                }
            }
            _logger.info("Flusher shutting down");
        }

        private void sleep() throws InterruptedException
        {
            synchronized (_lock)
            {
                while (_sleep && !_shutdown)
                {
                    _logger.debug("Flusher going to sleep");
                    _lock.wait();
                }
                _sleep = true;
            }
        }

        void wakeup()
        {
            synchronized (_lock)
            {
                if (_sleep)
                {
                    _logger.debug("Waking up flusher");
                    _sleep = false;
                    _lock.notify();
                }
            }
        }

        void shutdown()
        {
            _shutdown = true;
            wakeup();
        }
    }

    private class Worker extends Thread
    {
        private WorkerFlusher _flusher;

        public Worker()
        {
            super(SocketIoProcessor.this.threadName);
            _flusher = new WorkerFlusher();
            new Thread(_flusher, SocketIoProcessor.this.threadName + "Flusher").start();
        }

        public void run()
        {
            for (; ;)
            {
                try
                {
                    int nKeys = selector.select(1000);
                    doAddNew();
                    doUpdateTrafficMask();

                    if (nKeys > 0)
                    {
                        process(selector.selectedKeys());
                    }

                    //doFlush();
                    // in case the flusher has gone to sleep we wake it up
                    if (flushingSessions.size() > 0)
                    {
                        _flusher.wakeup();
                    }
                    doRemove();
                    notifyIdleness();

                    if (selector.keys().isEmpty())
                    {
                        synchronized (SocketIoProcessor.this)
                        {
                            if (selector.keys().isEmpty() &&
                                    newSessions.isEmpty())
                            {
                                worker = null;
                                _flusher.shutdown();
                                try
                                {
                                    selector.close();
                                }
                                catch (IOException e)
                                {
                                    ExceptionMonitor.getInstance().exceptionCaught(e);
                                }
                                finally
                                {
                                    selector = null;
                                }
                                break;
                            }
                        }
                    }
                }
                catch (Throwable t)
                {
                    ExceptionMonitor.getInstance().exceptionCaught(t);

                    try
                    {
                        Thread.sleep(1000);
                    }
                    catch (InterruptedException e1)
                    {
                    }
                }
            }
        }
    }

}
