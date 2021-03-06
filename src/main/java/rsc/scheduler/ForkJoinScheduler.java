package rsc.scheduler;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import rsc.flow.Disposable;
import rsc.util.*;

/**
 * Scheduler that hosts a fixed pool of single-threaded ForkJoinPool-based workers
 * and is suited for parallel work.
 */
public final class ForkJoinScheduler implements Scheduler {

    final int n;
    
    volatile ExecutorService[] executors;
    static final AtomicReferenceFieldUpdater<ForkJoinScheduler, ExecutorService[]> EXECUTORS =
            AtomicReferenceFieldUpdater.newUpdater(ForkJoinScheduler.class, ExecutorService[].class, "executors");

    static final ExecutorService[] SHUTDOWN = new ExecutorService[0];
    
    static final ExecutorService TERMINATED;
    static {
        TERMINATED = Executors.newSingleThreadExecutor();
        TERMINATED.shutdownNow();
    }
    
    int roundRobin;
    
    public ForkJoinScheduler() {
        this.n = Runtime.getRuntime().availableProcessors();
        init(n);
    }

    public ForkJoinScheduler(int n) {
        this.n = n;
        init(n);
    }

    private void init(int n) {
        ExecutorService[] a = new ExecutorService[n];
        for (int i = 0; i < n; i++) {
            a[i] = new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, false);
        }
        EXECUTORS.lazySet(this, a);
    }
    
    public int parallelism() {
        return n;
    }
    
    public boolean isStarted() {
        return executors != SHUTDOWN;
    }

    @Override
    public void start() {
        ExecutorService[] b = null;
        for (;;) {
            ExecutorService[] a = executors;
            if (a != SHUTDOWN) {
                if (b != null) {
                    for (ExecutorService exec : b) {
                        exec.shutdownNow();
                    }
                }
                return;
            }

            if (b == null) {
                b = new ExecutorService[n];
                for (int i = 0; i < n; i++) {
                    b[i] = new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, false);
                }
            }
            
            if (EXECUTORS.compareAndSet(this, a, b)) {
                return;
            }
        }
    }
    
    @Override
    public void shutdown() {
        ExecutorService[] a = executors;
        if (a != SHUTDOWN) {
            a = EXECUTORS.getAndSet(this, SHUTDOWN);
            if (a != SHUTDOWN) {
                for (ExecutorService exec : a) {
                    exec.shutdownNow();
                }
            }
        }
    }
    
    ExecutorService pick() {
        ExecutorService[] a = executors;
        if (a != SHUTDOWN) {
            // ignoring the race condition here, its already random who gets which executor
            int idx = roundRobin;
            if (idx == n) {
                idx = 0;
                roundRobin = 0;
            } else {
                roundRobin = idx + 1;
            }
            return a[idx];
        }
        return TERMINATED;
    }
    
    @Override
    public Disposable schedule(Runnable task) {
        ExecutorService exec = pick();
        Future<?> f = exec.submit(task);
        return () -> f.cancel(true);
    }

    @Override
    public Worker createWorker() {
        return new ParallelWorker(pick());
    }
    
    static final class ParallelWorker implements Worker {
        final ExecutorService exec;
        
        OpenHashSet<ParallelWorkerTask> tasks;
        
        volatile boolean shutdown;
        
        public ParallelWorker(ExecutorService exec) {
            this.exec = exec;
            this.tasks = new OpenHashSet<>();
        }

        @Override
        public Disposable schedule(Runnable task) {
            if (shutdown) {
                return REJECTED;
            }
            
            ParallelWorkerTask pw = new ParallelWorkerTask(task, this);
            
            synchronized (this) {
                if (shutdown) {
                    return REJECTED;
                }
                tasks.add(pw);
            }
            
            Future<?> f;
            try {
                f = exec.submit(pw);
            } catch (RejectedExecutionException ex) {
                UnsignalledExceptions.onErrorDropped(ex);
                return REJECTED;
            }
            
            if (shutdown) {
                f.cancel(true);
                return REJECTED; 
            }
            
            pw.setFuture(f);
            
            return pw;
        }

        @Override
        public void shutdown() {
            if (shutdown) {
                return;
            }
            shutdown = true;
            OpenHashSet<ParallelWorkerTask> set;
            synchronized (this) {
                set = tasks;
                tasks = null;
            }
            
            if (set != null && !set.isEmpty()) {
                Object[] a = set.keys();
                for (Object o : a) {
                    if (o != null) {
                        ((ParallelWorkerTask)o).cancelFuture();
                    }
                }
            }
        }
        
        void remove(ParallelWorkerTask task) {
            if (shutdown) {
                return;
            }
            
            synchronized (this) {
                if (shutdown) {
                    return;
                }
                tasks.remove(task);
            }
        }
        
        int pendingTasks() {
            if (shutdown) {
                return 0;
            }
            
            synchronized (this) {
                OpenHashSet<?> set = tasks;
                if (set != null) {
                    return set.size();
                }
                return 0;
            }
        }
        
        static final class ParallelWorkerTask implements Runnable, Disposable {
            final Runnable run;
            
            final ParallelWorker parent;
            
            volatile boolean cancelled;
            
            volatile Future<?> future;
            @SuppressWarnings("rawtypes")
            static final AtomicReferenceFieldUpdater<ParallelWorkerTask, Future> FUTURE =
                    AtomicReferenceFieldUpdater.newUpdater(ParallelWorkerTask.class, Future.class, "future");
            
            static final Future<Object> FINISHED = CompletableFuture.completedFuture(null);
            static final Future<Object> CANCELLED = CompletableFuture.completedFuture(null);
            
            public ParallelWorkerTask(Runnable run, ParallelWorker parent) {
                this.run = run;
                this.parent = parent;
            }
            
            @Override
            public void run() {
                if (cancelled || parent.shutdown) {
                    return;
                }
                try {
                    try {
                        run.run();
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);
                        UnsignalledExceptions.onErrorDropped(ex);
                    }
                } finally {
                    for (;;) {
                        Future<?> f = future;
                        if (f == CANCELLED) {
                            break;
                        }
                        if (FUTURE.compareAndSet(this, f, FINISHED)) {
                            parent.remove(this);
                            break;
                        }
                    }
                }
            }
            
            @Override
            public void dispose() {
                if (!cancelled) {
                    cancelled = true;
                    
                    Future<?> f = future;
                    if (f != CANCELLED && f != FINISHED) {
                        f = FUTURE.getAndSet(this, CANCELLED);
                        if (f != CANCELLED && f != FINISHED) {
                            if (f != null) {
                                f.cancel(true);
                            }
                            
                            parent.remove(this);
                        }
                    }
                }
            }
            
            void setFuture(Future<?> f) {
                if (future != null || !FUTURE.compareAndSet(this, null, f)) {
                    if (future != FINISHED) {
                        f.cancel(true);
                    }
                }
            }
            
            void cancelFuture() {
                Future<?> f = future;
                if (f != CANCELLED && f != FINISHED) {
                    f = FUTURE.getAndSet(this, CANCELLED);
                    if (f != null && f != CANCELLED && f != FINISHED) {
                        f.cancel(true);
                    }
                }
            }
        }
    }
}
