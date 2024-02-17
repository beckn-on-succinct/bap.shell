package in.succinct.bap.shell.controller.proxies;

import com.venky.core.io.Locker;
import com.venky.core.util.Bucket;
import com.venky.swf.db.Database;
import com.venky.swf.plugins.background.core.TaskManager;
import com.venky.swf.plugins.background.eventloop.CoreEvent;
import in.succinct.bap.shell.db.model.BecknAction;
import in.succinct.bap.shell.db.model.BecknTransaction;
import in.succinct.beckn.BecknException;
import in.succinct.beckn.Context;
import in.succinct.beckn.Error;
import in.succinct.beckn.Error.Type;
import in.succinct.beckn.Message;
import in.succinct.beckn.Order;
import in.succinct.beckn.Request;
import in.succinct.beckn.SellerException;
import in.succinct.beckn.Subscriber;
import org.json.simple.JSONObject;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    final Map<String,Tracker> responseMessages = new HashMap<>(){
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

    public Tracker open(Request request){
        return responseMessages.get(request.getContext().getMessageId());
    }

    public void start (Request request, int maxResponses){
        Tracker tracker = responseMessages.get(request.getContext().getMessageId());
        if (!tracker.isStarted()){
            tracker.start(request,maxResponses);
            service.schedule(() -> {
                //Timer thread.
                shutdown(request.getContext().getMessageId());
            }, request.getContext().
                    getTtl() * 1000L, TimeUnit.MILLISECONDS);
        }
    }
    private ScheduledExecutorService service = Executors.newScheduledThreadPool(1);


    public void addResponse(Request request){
        String messageId = request.getContext().getMessageId();
        Tracker tracker = getTracker(messageId);
        if (tracker != null) {
            tracker.addResponse(request);
        }else {
            tracker = responseMessages.get(messageId);
            tracker.addResponse(request);
            close(messageId);
        }
    }
    public void close(String messageId){
        synchronized (responseMessages) {
            responseMessages.remove(messageId);
        }
    }
    public void shutdown(String messageId){
        synchronized (responseMessages) {
            if (responseMessages.containsKey(messageId)) {
                Tracker tracker = responseMessages.get(messageId);
                if (tracker != null) {
                    tracker.shutdown();
                }
            }
        }
    }
    public boolean isRequestActive(String messageId){
        return getTracker(messageId) != null;
    }
    private Tracker getTracker(String messageId){
        Tracker tracker = null;
        synchronized (responseMessages) {
            if (responseMessages.containsKey(messageId)) {
                tracker = responseMessages.get(messageId);
            }
        }
        return tracker;
    }

    public Request getNextResponse(String messageId){
        Tracker tracker = getTracker(messageId);
        if (tracker != null) {
            return tracker.nextResponse();
        }
        return null;
    }
    public boolean isComplete(String messageId){
        Tracker tracker = getTracker(messageId);
        if (tracker != null ){
            return tracker.isComplete();
        }
        return true;
    }




    public static class Tracker {
        long start;
        long end ;
        Bucket pendingResponses;
        CoreEvent sourceEvent;
        boolean shutdown = false;
        BecknAction action ;
        Request request = null;
        public Tracker(){

        }
        private void start(Request request,int maxResponses){
            synchronized (this) {
                if (this.start <= 0) {
                    this.start = request.getContext().getTimestamp().getTime();
                    this.end = this.start + request.getContext().getTtl() * 1000L;
                    this.sourceEvent = CoreEvent.getCurrentEvent();
                    this.pendingResponses = new Bucket(maxResponses);
                    this.request = request;
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
                    trigger();
                }
            }
        }
        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        public boolean isStarted(){
            return start > 0;
        }

        public boolean isResponsesCollected(){
            return  shutdown || ( start > 0 && (end < System.currentTimeMillis()) ) || (pendingResponses != null && pendingResponses.intValue() <= 0) ;
        }

        public boolean isComplete(){
            return isResponsesCollected() && responses.isEmpty();
        }

        public Request nextResponse(){
            synchronized (this) {
                if (!responses.isEmpty()) {
                    return responses.removeFirst();
                }
            }
            if (isComplete()){
                if (request != null) {
                    if (ResponseSynchronizer.getInstance().isRequestActive(request.getContext().getMessageId())) {
                        ResponseSynchronizer.getInstance().close(request.getContext().getMessageId());
                        Request dummy = new Request();
                        dummy.setContext(new Context());
                        dummy.getContext().update(request.getContext());
                        dummy.setError(new Error());
                        dummy.getError().setType(Type.DOMAIN_ERROR);
                        BecknException sellerException = new SellerException.NoDataAvailable();
                        dummy.getError().setCode(sellerException.getErrorCode());
                        dummy.getError().setMessage(sellerException.getMessage());
                        return dummy;
                    }
                }
            }
            return null;
        }
        public void shutdown(){
            this.shutdown = true;
            trigger();
        }

        public void trigger() {
            if (sourceEvent != null) {
                TaskManager.instance().executeAsync(sourceEvent, false);
            }
        }
    }

}
