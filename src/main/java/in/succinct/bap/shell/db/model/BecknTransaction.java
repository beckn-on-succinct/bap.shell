package in.succinct.bap.shell.db.model;

import com.venky.swf.db.annotations.column.COLUMN_SIZE;
import com.venky.swf.db.annotations.column.UNIQUE_KEY;
import com.venky.swf.db.annotations.column.indexing.Index;
import com.venky.swf.db.model.Model;

import java.util.List;

@SuppressWarnings("unused")
public interface BecknTransaction extends Model {
    @UNIQUE_KEY
    @Index
    public String getTransactionId();
    public void setTransactionId(String transactionId);

    @COLUMN_SIZE(2^16)
    public String getOrderJson();
    public void setOrderJson(String orderJson);

    public List<BecknAction> getActions();
}

