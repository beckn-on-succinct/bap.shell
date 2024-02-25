package in.succinct.bap.shell.controller.proxies;

import com.venky.core.util.Bucket;
import com.venky.swf.db.Database;
import com.venky.swf.plugins.background.core.AsyncTaskManagerFactory;
import com.venky.swf.plugins.background.eventloop.CoreEvent;
import in.succinct.bap.shell.db.model.BecknAction;
import in.succinct.bap.shell.db.model.BecknTransaction;
import in.succinct.beckn.Order;
import in.succinct.beckn.Request;
import in.succinct.beckn.Subscriber;

import javax.sound.midi.Track;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused")
public class ResponseSynchronizer {
    private static volatile ResponseSynchronizer sSoleInstance;

    //private constructor.
    private ResponseSynchronizer() {
        //Prevent form the reflection api.
        if (sSoleInstance != null) {
            throw new RuntimeException("Use getInstance() method to get the single instance of this class.");
        }
    }

    public static ResponseSynchronizer getInstance() {
        if (sSoleInstance == null) { //if there is no instance available... create new one
            synchronized (ResponseSynchronizer.class) {
                if (sSoleInstance == null) sSoleInstance = new ResponseSynchronizer();
            }
        }

        return sSoleInstance;
    }

    //Make singleton from serialize and deserialize operation.
    protected ResponseSynchronizer readResolve() {
        return getInstance();
    }

    final HashMap<String,Tracker> responseMessages = new HashMap<>(){
        @Override
        public Tracker get(Object key) {
            Tracker tracker = super.get(key);
            if (tracker == null){
                synchronized (this){
                    tracker = super.get(key);
                    if (tracker == null){
                        tracker = new Tracker();
                        put((String)key,tracker);
                    }
                }
            }
            return tracker;
        }

    };
    public Tracker createTracker(Request request){
        return responseMessages.get( request.getContext().getMessageId());
    }

    public Tracker getTracker(String messageId, boolean returnNewIfNone){
        synchronized (responseMessages) {
            if (responseMessages.containsKey(messageId)) {
                return responseMessages.get(messageId);
            }else if (returnNewIfNone){
                return new Tracker();
            }
        }
        return null;
    }
    private static final ScheduledExecutorService service = Executors.newScheduledThreadPool(1);

    public void addResponse(Request response){
        String messageId = response.getContext().getMessageId();
        Tracker tracker = getTracker(messageId,true);
        tracker.addResponse(response);
    }
    public void closeTracker(String messageId){
        synchronized (responseMessages) {
            Tracker tracker = responseMessages.remove(messageId);
        }
    }

    public static class Tracker {
        long start;
        long end ;
        Bucket pendingResponses;
        boolean shutdown = false;
        BecknAction action ;
        Request request = null;
        CoreEvent watcher = null;
        ScheduledFuture<?> keepAliveTrigger = null;
        ScheduledFuture<?> shutDownTrigger = null;

        public Tracker(){

        }
        public void start(Request request,int maxResponses){
            synchronized (this) {
                if (this.start <= 0) {
                    this.start = request.getContext().getTimestamp().getTime();
                    this.end = this.start + request.getContext().getTtl() * 1000L;
                    this.pendingResponses = new Bucket(maxResponses);
                    this.request = request;
                    this.keepAliveTrigger = service.scheduleWithFixedDelay(()->{
                        notifyWatcher();
                    },5000L,10000L ,TimeUnit.MILLISECONDS);
                    this.shutDownTrigger = service.schedule(()->{
                        shutdown();
                    },request.getContext().getTtl() *1000L,TimeUnit.MILLISECONDS);
                }
            }
        }
        private BecknAction initializeBecknTransaction(Request request){
            BecknTransaction becknTransaction = Database.getTable(BecknTransaction.class).newRecord();
            becknTransaction.setTransactionId(request.getContext().getTransactionId());
            becknTransaction = Database.getTable(BecknTransaction.class).getRefreshed(becknTransaction);
            if (Subscriber.BAP_ACTION_SET.contains(request.getContext().getAction())){
                Order order = request.getMessage().getOrder();
                if (order != null){
                    String orderJson = becknTransaction.getOrderJson();
                    Order persisted = null;
                    if (orderJson != null){
                        persisted = new Order(orderJson);
                        persisted.update(order);
                    }else {
                        persisted = order;
                    }
                    becknTransaction.setOrderJson(persisted.getInner().toString());
                }
            }
            becknTransaction.save();
            BecknAction action = Database.getTable(BecknAction.class).newRecord();
            action.setBecknTransactionId(becknTransaction.getId());
            action.setMessageId(request.getContext().getMessageId());
            return action;
        }

        private final LinkedList<Request> responses = new LinkedList<>();

        @SuppressWarnings("unchecked")
        public void addResponse(Request response){
            synchronized (this) {
                boolean unsolicited = !isStarted();

                action = initializeBecknTransaction(response);
                if (request != null) {
                    action.setRequest(request.toString());
                }
                action.setResponse(response.toString());
                action.save();
                if (this.pendingResponses != null){
                    this.pendingResponses.decrement();
                }
                if (!unsolicited) {
                    responses.add(response);
                    notifyWatcher();
                }
            }
        }
        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        private boolean isStarted(){
            synchronized (this) {
                return start > 0;
            }
        }

        private boolean isResponsesCollected(){
            synchronized (this) {
                return shutdown || (start > 0 && (end < System.currentTimeMillis())) || (pendingResponses != null && pendingResponses.intValue() <= 0);
            }
        }

        public boolean isComplete(){
            synchronized (this) {
                return isResponsesCollected() && responses.isEmpty();
            }
        }

        public Request nextResponse(){
            synchronized (this) {
                if (!responses.isEmpty()) {
                    return responses.removeFirst();
                }
            }

            return null;
        }

        public void shutdown(){
            synchronized (this) {
                this.shutdown = true;
                if (this.keepAliveTrigger != null && !this.keepAliveTrigger.isCancelled()) {
                    this.keepAliveTrigger.cancel(false);
                }
                if (this.shutDownTrigger != null && !this.shutDownTrigger.isCancelled()) {
                    this.shutDownTrigger.cancel(false);
                }
                if (this.watcher == null){
                    if (request != null) {
                        ResponseSynchronizer.getInstance().closeTracker(request.getContext().getMessageId());
                    }
                }else {
                    notifyWatcher();
                }
            }
        }

        public void notifyWatcher() {
            synchronized (this) {
                if (watcher != null) {
                    AsyncTaskManagerFactory.getInstance().addAll(Collections.singleton(watcher));
                    watcher = null;
                }
            }
        }

        public void startWatching() {
            synchronized (this) {
                if (watcher == null) {
                    watcher = CoreEvent.getCurrentEvent();
                } else if (watcher != CoreEvent.getCurrentEvent()) {
                    throw new RuntimeException("Some other thread is watching this");
                }
            }
        }

        public boolean isWatched(){
            synchronized (this) {
                return watcher != null;
            }
        }



    }

}
