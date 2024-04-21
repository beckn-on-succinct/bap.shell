package in.succinct.bap.shell.db.model;

import com.venky.swf.db.annotations.column.COLUMN_SIZE;
import com.venky.swf.db.annotations.column.IS_NULLABLE;
import com.venky.swf.db.annotations.column.indexing.Index;
import com.venky.swf.db.annotations.model.ORDER_BY;
import com.venky.swf.db.model.Model;

@ORDER_BY("ID ASC")
public interface BecknAction extends Model {
    @Index
    @IS_NULLABLE(false)
    public Long getBecknTransactionId();
    public void setBecknTransactionId(Long id);
    public BecknTransaction getBecknTransaction();

    @Index
    public String getMessageId();
    public void setMessageId(String messageId);
    
    @COLUMN_SIZE(4096*4)
    @IS_NULLABLE
    public String getRequest();
    public void setRequest(String request);

    @COLUMN_SIZE(4096*4)
    @IS_NULLABLE
    public String getResponse();
    public void setResponse(String responses);



}
