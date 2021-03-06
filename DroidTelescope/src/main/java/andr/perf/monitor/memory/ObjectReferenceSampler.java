package andr.perf.monitor.memory;

import android.content.ComponentCallbacks2;
import android.os.Build;
import android.os.Looper;
import android.os.MessageQueue;

import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import andr.perf.monitor.DroidTelescope;
import andr.perf.monitor.JobManager;
import andr.perf.monitor.SamplerFactory;
import andr.perf.monitor.memory.models.LeakInfo;
import andr.perf.monitor.utils.Logger;

/**
 * Created by ZhouKeWen on 17/4/1.
 */
public class ObjectReferenceSampler {

    private static final String TAG = "ObjectReferenceSampler";

    /**
     * 最大标记次数，超过则视为垃圾
     */
    private static final int MAX_MARK_TIMES = 2;

    /**
     * 在同一个UI线程里操作嫌疑对象列表，所以无需加锁
     */
    private List<SuspectWeakReference> suspectObjects = new LinkedList<>();
    /**
     * 在UI线程里操作对象创建堆栈，所以无需加锁
     */
    private Deque<String> objectCreateStack = new LinkedList<>();

    /**
     * 触发gc和引用扫描任务的最小时间间隔
     */
    private static final long TRIGGER_INTERVAL_TIME = 100;//100ms

    private long triggerTime;

    private Runnable gcTask = new Runnable() {
        @Override
        public void run() {
            // 触发gc会消耗CPU20ms左右，所以异步触发
            Runtime.getRuntime().gc();
            Runtime.getRuntime().runFinalization();
            //将扫描引用任务放到主线程的idle里
            Looper.getMainLooper().getQueue().addIdleHandler(scanReferenceTask);
        }
    };

    //一次post任务到Looper要消耗1ms左右，所以当任务执行时间小于1ms，则不适用异步
    //==============实验证明，由于任务耗时很短，异步执行反而耗时变长，所以改成同步调用=========

    private MessageQueue.IdleHandler scanReferenceTask = new MessageQueue.IdleHandler() {

        @Override
        public boolean queueIdle() {
            Logger.i("zkw", "[---]start find garbage!!!!!");
            if (suspectObjects == null || suspectObjects.isEmpty()) {
                Logger.i(TAG, "[---]===============all clean!!!");
                return false;
            }
            Iterator<SuspectWeakReference> iterator = suspectObjects.iterator();
            LeakInfo leakInfo = new LeakInfo();
            while (iterator.hasNext()) {
                SuspectWeakReference reference = iterator.next();
                Object obj = reference.get();
                if (obj == null) {
                    iterator.remove();
                } else {
                    //增加对象的计数，当达到阈值时视为泄漏
                    reference.increaseLookUpTimes();
                    Logger.i(TAG, "[---]times:" + reference.getMarkeTimes());
                    if (reference.getMarkeTimes() > MAX_MARK_TIMES) {
                        //发生泄漏，记录泄漏Object
                        Logger.i(TAG, "[---]...............garbage!!!!!!!>> " + obj.toString());
                        iterator.remove();
                        leakInfo.addGarbageReference(reference);
                    }
                }
            }
            //在UI线程回调
            DroidTelescope.LeakListener listener = DroidTelescope.getLeakListener();
            if (listener != null && leakInfo.getReferenceList() != null &&
                    !leakInfo.getReferenceList().isEmpty()) {
                listener.onLeak(leakInfo);
            }
            return false;
        }
    };

    public ObjectReferenceSampler() {
    }

    public void onKeyObjectCreate(final Object object) {
        //维护关键对象create栈，用于记录对象的create链
        objectCreateStack.push(object.toString());
    }

    public void onKeyObjectDestroy(final Object object) {
        //记录object的create栈，并存入SuspectWeakReference对象，一个Reference对应一个调用栈
        SuspectWeakReference reference = new SuspectWeakReference(object);
        String[] objectIdStack = objectCreateStack.toArray(new String[objectCreateStack.size()]);
        reference.setCreateStack(objectIdStack);
//        IEvent[] events = SamplerFactory.getInteractiveSampler().obtainCurrentEvents();
//        reference.setViewEventArray(events);
        suspectObjects.add(reference);
        //防止堆栈混乱，索性直接用remove
        objectCreateStack.remove(object.toString());
        triggerGC();
    }

    public void onLowMemory(Object object) {
        onTrimMemory(object, ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
    }

    public void onTrimMemory(Object object, int level) {
        triggerGC();
    }

    private void triggerGC() {
        //避免过于频繁的触发gc和引用扫描任务
        long timestamp = System.currentTimeMillis();
        if (timestamp - triggerTime > TRIGGER_INTERVAL_TIME) {
            //由于触发gc要消耗20ms左右，所以异步触发
            JobManager.getInstance().postWorkerThread(gcTask);
        }
        triggerTime = timestamp;
    }

}
