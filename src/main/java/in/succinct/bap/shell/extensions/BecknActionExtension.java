package in.succinct.bap.shell.extensions;

import com.venky.core.io.ByteArrayInputStream;
import com.venky.core.string.StringUtil;
import com.venky.core.util.ObjectUtil;
import com.venky.swf.db.Database;
import com.venky.swf.db.extensions.ModelOperationExtension;
import com.venky.swf.plugins.background.core.Task;
import com.venky.swf.plugins.background.core.TaskManager;
import in.succinct.bap.shell.db.model.BecknAction;
import in.succinct.bap.shell.db.model.BecknTransaction;
import in.succinct.beckn.Catalog;
import in.succinct.beckn.Catalogs;
import in.succinct.beckn.Categories;
import in.succinct.beckn.Category;
import in.succinct.beckn.Fulfillment;
import in.succinct.beckn.FulfillmentStop;
import in.succinct.beckn.Fulfillments;
import in.succinct.beckn.Item;
import in.succinct.beckn.Items;
import in.succinct.beckn.Location;
import in.succinct.beckn.Locations;
import in.succinct.beckn.Order;
import in.succinct.beckn.Order.NonUniqueItems;
import in.succinct.beckn.Payment;
import in.succinct.beckn.Payments;
import in.succinct.beckn.Provider;
import in.succinct.beckn.Request;


import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class BecknActionExtension extends ModelOperationExtension<BecknAction> {
    static {
        registerExtension(new BecknActionExtension());
    }
    public static class MergeMetaData implements Task {
        BecknAction instance;
        public MergeMetaData(BecknAction instance){
            this.instance = instance;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            MergeMetaData that = (MergeMetaData) o;

            return Objects.equals(instance, that.instance);
        }

        @Override
        public int hashCode() {
            return instance != null ? Long.hashCode(instance.getId()) : 0;
        }

        @Override
        public void execute() {
            Request request = instance.getRequest() == null ? null : new Request(instance.getRequest());
            Request response = instance.getResponse() == null ? null : new Request(instance.getResponse());
            BecknTransaction transaction = Database.getTable(BecknTransaction.class).lock(instance.getBecknTransactionId());
            BecknTransaction searchTransaction = transaction;
            if (!ObjectUtil.isVoid(transaction.getSearchTransactionId())){
                searchTransaction = BecknTransaction.find(transaction.getSearchTransactionId());
            }

            Order order = transaction.getOrderJson() == null ? null : new Order(transaction.getOrderJson());
            Catalogs catalogs = searchTransaction.getCatalogJson() == null ? null : new Catalogs(StringUtil.read(searchTransaction.getCatalogJson()));

            Order responseOrder = response == null ? null : response.getMessage().getOrder();
            Catalog responseCatalog = response == null ? null : response.getMessage().getCatalog();

            Order requestOrder = request == null ? null : request.getMessage().getOrder();
            if (requestOrder != null){
                if (order != null){
                    order.update(requestOrder);
                }
            }

            if (responseOrder != null){
                if (order == null){
                    order = responseOrder;
                }else {
                    order.update(responseOrder);
                }
                Catalog catalog = catalogs == null ? null : catalogs.get(response.getContext().getBppId());
                if (catalog != null){
                    Provider provider = order.getProvider();
                    Fulfillments fulfillments = order.getFulfillments();
                    Fulfillment fulfillment = fulfillments != null && !fulfillments.isEmpty() ? fulfillments.get(0) : null ;
                    Payments payments = order.getPayments();
                    Payment payment = payments != null && !payments.isEmpty() ? payments.get(0) : null ;
                    
                    NonUniqueItems nonUniqueItems = getItems(order, fulfillment); //0.9.3

                    if (provider != null){
                        Provider catalogProvider = catalog.getProviders().get(provider.getId()) ;
                        provider.setDescriptor(catalogProvider.getDescriptor());
                        if (provider.getLocations() == null){
                            provider.setLocations(new Locations());
                        }
                        if (provider.getFulfillments() == null){
                            provider.setFulfillments(new Fulfillments());
                        }
                        if (provider.getPayments() == null){
                            provider.setPayments(new Payments());
                        }
                        if (provider.getCategories() == null){
                            provider.setCategories(new Categories());
                        }

                        Categories catalogCategories = catalogProvider.getCategories() == null ? catalog.getCategories() : catalogProvider.getCategories();
                        for (Category c : provider.getCategories()){
                            c.update(catalogCategories.get(c.getId()));
                        }
                        Payments catalogPayments = catalogProvider.getPayments() == null ? catalog.getPayments() : catalogProvider.getPayments();

                        for (Payment p : provider.getPayments()) {
                            if (catalogPayments != null) {
                                p.update(catalogPayments.get(p.getId()), false);
                            }
                        }
                        if (payment != null && catalogPayments != null){
                            payment.update(catalogPayments.get(payment.getId()),false);
                        }

                        Locations catalogLocations = catalogProvider.getLocations();
                        for (Location l : provider.getLocations()) {
                            l.update(catalogLocations.get(l.getId()));
                        }
                        Fulfillments catalogFulfillments  = catalogProvider.getFulfillments();
                        for (Fulfillment f : provider.getFulfillments()){
                            f.update(catalogFulfillments.get(f.getId()));
                        }
                        if (fulfillment != null){
                            fulfillment.update(catalogFulfillments.get(fulfillment.getId()),false);
                            FulfillmentStop start = fulfillment._getStart();
                            Location startLocation =  start == null ? null :start.getLocation();
                            if (startLocation != null && startLocation.getId() != null){
                                startLocation.update(catalogLocations.get(startLocation.getId()));
                            }
                        }
                        Items catalogItems  = catalogProvider.getItems();
                        for (Item i: nonUniqueItems){
                            i.setDescriptor(catalogItems.get(i.getId()).getDescriptor());
                        }
                    }
                }

                transaction.setOrderJson(order.getInner().toString());
            }else if (responseCatalog != null){
                if (catalogs == null) {
                    catalogs = new Catalogs();
                }
                responseCatalog.setId(response.getContext().getBppId());

                Catalog existing = catalogs.get(responseCatalog.getId());
                if (existing == null){
                    catalogs.add(responseCatalog);
                }else {
                    existing.update(responseCatalog);
                }
                transaction.setCatalogJson(new ByteArrayInputStream(catalogs.getInner().toString().getBytes(StandardCharsets.UTF_8)));
            }
            transaction.save();
        }

        private static NonUniqueItems getItems(Order order, Fulfillment fulfillment) {
            NonUniqueItems nonUniqueItems = order.getItems();
            if (fulfillment != null) {
                int i = nonUniqueItems.size() - 1;
                while (i >= 0) {
                    Item item = nonUniqueItems.get(i);
                    if (!ObjectUtil.equals(fulfillment.getId(), item.getFulfillmentId())) {
                        nonUniqueItems.remove(i);
                    }
                    i--;
                }
            }
            return nonUniqueItems;
        }
    }
    @Override
    protected void afterSave(BecknAction instance) {
        super.afterSave(instance);
        TaskManager.instance().executeAsync(new MergeMetaData(instance),false);
    }
}
