package in.succinct.bap.shell.db.model;

import com.venky.swf.db.annotations.column.IS_VIRTUAL;
import com.venky.swf.db.annotations.column.indexing.Index;
import com.venky.swf.db.table.ModelImpl;
import in.succinct.beckn.Billing;
import in.succinct.beckn.Fulfillment;
import in.succinct.beckn.FulfillmentStop;
import in.succinct.beckn.Fulfillments;
import in.succinct.beckn.Order;
import in.succinct.beckn.Order.Status;
import in.succinct.beckn.Payment;
import in.succinct.beckn.Payment.PaymentStatus;
import in.succinct.beckn.Payments;

import java.util.Arrays;

public class BecknTransactionImpl extends ModelImpl<BecknTransaction> {
    public BecknTransactionImpl(BecknTransaction proxy){
        super(proxy);
    }
    private Order getOrder(){
        BecknTransaction transaction = getProxy();
        String oj  =transaction.getOrderJson();
        return oj == null ? null : new Order(oj);
    }
    public String getFulfillmentStartJson(){
        Order order = getOrder();
        Fulfillment f = getFulfillment(order);
        FulfillmentStop start = f == null ? null : f.getStart() ;
        return start == null ? null : start.getInner().toString();
    
    }
    public Fulfillment getFulfillment(Order order) {
        Fulfillments fulfillments = order == null ? null : order.getFulfillments();
        Fulfillment f = null;
        if (fulfillments != null && !fulfillments.isEmpty()) {
            f = fulfillments.get(0);
        }
        return f;
    }

    public String getFulfillmentEndJson(){
        Order order = getOrder();
        Fulfillment f = getFulfillment(order);
        FulfillmentStop end = f == null ? null : f.getEnd() ;
        return end == null ? null : end.getInner().toString();
    }

    public String getBillingJson(){
        Order order = getOrder();
        Billing b = order == null ? null : order.getBilling();
        return b == null ? null : b.getInner().toString();
    }
    
    
    public String getPaymentJson() {
        Order order = getOrder();
        Payments b = order == null ? null : order.getPayments();
        return b == null ? null : b.getInner().toString();
    }

    public boolean isOpen(){
        BecknTransaction bt = getProxy();
        if (bt.getOrderJson() == null){
            return false;
        }
        Order order = new Order(bt.getOrderJson());
        if (order.getId() == null){
            return false;
        }

        if (!Arrays.asList(Status.Completed,Status.Cancelled).contains(order.getStatus())){
            return true;
        }
        Payments payments = order.getPayments();
        Payment p = payments == null || payments.isEmpty() ? null : payments.get(0);
        
        
        return p == null || p.getStatus() != PaymentStatus.PAID;

    }
}
