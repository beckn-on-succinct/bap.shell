package in.succinct.bap.shell.db.model;

import com.venky.swf.db.Database;
import com.venky.swf.db.annotations.column.COLUMN_SIZE;
import com.venky.swf.db.annotations.column.IS_NULLABLE;
import com.venky.swf.db.annotations.column.IS_VIRTUAL;
import com.venky.swf.db.annotations.column.UNIQUE_KEY;
import com.venky.swf.db.annotations.column.indexing.Index;
import com.venky.swf.db.annotations.column.pm.PARTICIPANT;
import com.venky.swf.db.annotations.column.ui.HIDDEN;
import com.venky.swf.db.model.Model;
import com.venky.swf.db.model.User;

import java.io.InputStream;
import java.util.List;

@SuppressWarnings("unused")
public interface BecknTransaction extends Model {
    @UNIQUE_KEY
    @Index
    public String getTransactionId();
    public void setTransactionId(String transactionId);

    @Index
    @IS_NULLABLE
    public String getSearchTransactionId();
    public void setSearchTransactionId(String searchTransactionId);

    @COLUMN_SIZE(65536)
    public String getOrderJson();
    public void setOrderJson(String orderJson);

    @HIDDEN
    public InputStream getCatalogJson();
    public void setCatalogJson(InputStream catalogJson);

    @Index
    @IS_VIRTUAL
    public String getFulfillmentStartJson();

    @Index
    @IS_VIRTUAL
    public String getFulfillmentEndJson();

    @Index
    @IS_VIRTUAL
    public String getBillingJson();

    @Index
    @IS_VIRTUAL
    public String getPaymentJson();


    @Index
    @IS_VIRTUAL
    public boolean isOpen();





    public List<BecknAction> getActions();
    public static BecknTransaction find(String transactionId){
        return find(transactionId,BecknTransaction.class);
    }
    public static <T extends BecknTransaction> T find(String transactionId , Class<T> clazz){
        T t = Database.getTable(clazz).newRecord();
        t.setTransactionId(transactionId);
        t = Database.getTable(clazz).find(t,false);
        return t;
    }
}

