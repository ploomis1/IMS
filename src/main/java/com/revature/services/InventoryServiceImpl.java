package com.revature.services;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.revature.beans.Contract;
import com.revature.beans.Item;
import com.revature.beans.ItemPrimaryKey;
import com.revature.beans.Order;
import com.revature.beans.Store;
import com.revature.repositories.ContractRepository;
import com.revature.repositories.ItemRepository;
import com.revature.repositories.StoreRepository;
import com.revature.utils.DistanceMath;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
@Service
public class InventoryServiceImpl implements InventoryService {
	private static Logger log = LogManager.getLogger();
	private ItemRepository itemRepo;
	@Autowired
	public void setItemRepo(ItemRepository itemRepo) {
		this.itemRepo=itemRepo;
	}private ContractRepository conRepo;
	@Autowired
	public void setContractRepo(ContractRepository conRepo) {
		this.conRepo=conRepo;
	}private StoreRepository storeRepo;
	@Autowired
	public void setStoreRepo(StoreRepository storeRepo) {
		this.storeRepo=storeRepo;
	}
	
	private List<Item> getListOfItemsWithHighQuantity(Flux<Item> high){
		List<Item> highList;
		try {
		    highList=high.collectList().toFuture().get();
		  } catch (InterruptedException e) {
		    Thread.currentThread().interrupt();
		    log.error("Interrupted surplusInv tolist", e);
		    return null;
		  } catch (ExecutionException e) {
		    log.error("execution surplusInv tolist", e);
		    return null;
		  }
		return highList;
	}
	
	private Map<Integer,Store> getMapOfStores(){
		Map<Integer,Store> stores;
		  try {
			    stores= storeRepo.findAll().collectMap(s->s.getId()).toFuture().get();
			  } catch (InterruptedException e) {
			    Thread.currentThread().interrupt();
			    log.error("Interrupted stores tolist", e);
			    return null;
			  } catch (ExecutionException e) {
			    log.error("execution stores tolist", e);

			    return null;
			  }
		  return stores;
	}
	
	private List<Contract> getListOfContracts(){
		List<Contract> cons;
		  try {
			    cons= conRepo.findAll().collectList().toFuture().get();
			  } catch (InterruptedException e) {
			    Thread.currentThread().interrupt();
			    log.error("Interrupted contracts tolist", e);
			    return null;
			  } catch (ExecutionException e) {
			    log.error("execution contracts tolist", e);
			    return null;
			  }
		return cons;
	}
	
	@Override
	public Flux<Item> autoOrder(){
	  Flux<Item> everything = itemRepo.findAll();
	  Flux<Item> high= everything.filter(i->(i.getQuantity()>i.getOverstockThreshold()));
	  Flux<Item> low= everything.filter(i->(i.getQuantity()<=i.getLowThreshold()));
	  return addItemsToStoreRecievingInventoryAfterOrder(low,high);
	}
	@Override
	public Flux<Item> createManualOrder(Flux<Item> manualOrders){
	  Flux<Item> everything = itemRepo.findAll();
	  Flux<Item> high= everything.filter(i->(i.getQuantity()>i.getOverstockThreshold()));
	  return addItemsToStoreRecievingInventoryAfterOrder(manualOrders,high);
	}

	private Order autoOrders(int itemId, int itemStoreId, int amount,List<Item> highList, Map<Integer,Store> stores, List<Contract> cons) {
	  //Atomic integer array counts as effectively final but can still be modified in lambda
	  AtomicIntegerArray nuclear= new AtomicIntegerArray(highList.stream().mapToInt(i->i.getQuantity()-i.getOverstockThreshold()).toArray());
	    //couldn't do it the other way cause it ddos'd aws
	    Contract c=cons.stream().filter(con->con.getItemId()==itemId).findFirst().orElseGet(null);
	    if (c==null) {
	      log.error("someone didn't enforce relationships, contract is null... id is {0}",String.valueOf(itemId));
	      return null;
	    }
	    //but we order by palette
	    int cAmount= amount/c.getQuantity()+((amount%c.getQuantity()==0)? 0:1);//amount of palettes
	    cAmount=cAmount*c.getQuantity();//back to amount of items
	    double price=cAmount*c.getItemCost();
	    //but gotta see if this is at least min order cost
	    if(price<c.getMinOrderCost()) {
	      //how many items we need to get to cost
	      cAmount= (int) (c.getMinOrderCost()/c.getItemCost()+((c.getMinOrderCost()%c.getItemCost()==0)? 0:1));
	      //rounding up by palette size
	      cAmount= cAmount/c.getQuantity()+((cAmount%c.getQuantity()==0)? 0:1);//amount of palettes
	      cAmount=cAmount*c.getQuantity();//back to amount of items
	      //new price
	      price=cAmount*c.getItemCost();
	    }
	    if(nuclear.length()>0) {
	      Item bestDis =highList.stream().filter(item->(nuclear.get(highList.indexOf(item))>=amount))//find where a surplus would actually be enough
	          //sorts stores by distance 
	          .sorted((a,b)->Double.compare(distance(a.getStoreId(),itemStoreId,stores),distance(b.getStoreId(),itemStoreId,stores))).findFirst().orElseGet(null);
	      if (bestDis!=null) {
	        Store distribStore=stores.get(bestDis.getStoreId());
	        //as far as i know we never defined our inter store shipping cost so it's 0.03
	        double dprice=amount*c.getItemCost()+ amount*c.getShippingCost()*distance(bestDis.getStoreId(),itemStoreId,stores);
	        //so it checks if the cost is cheaper than buying from distributor but it also checks to see if it would be worth it to ship
	        //basically if it would cost more than half of the profit per item to ship it's considered non viable
	        if (dprice<price&& dprice<(amount*c.getItemCost()+ (c.getItemMSRP()-c.getItemCost())*amount/2)) {
	          //update that the surplus has changed.. set atomic array at position of surplus to previous surplus - amount
	          nuclear.set(highList.indexOf(bestDis), nuclear.get(highList.indexOf(bestDis))-amount);
	          return new Order(itemId,itemStoreId,amount,dprice,""+distribStore.getId(),false);
	        }
	      }
	    }
	    return new Order(itemId,itemStoreId, cAmount, price,c.getSupplier(),true);
	  
	}

	private double distance(int id1, int id2, Map<Integer,Store> stores) {
	  Store s1= stores.get(id1);
	  Store s2= stores.get(id2);
	  if(s1==null||s2==null)
	    return Double.MAX_VALUE;
	  return DistanceMath.kmBetweenTwoCoords(s1.getLocation(), s2.getLocation());		
	}
	
	public Flux<Item> addItemsToStoreRecievingInventoryAfterOrder(Flux<Item> items, Flux<Item> high){
		  List<Item> highList= getListOfItemsWithHighQuantity(high);
		  Map<Integer,Store> stores=getMapOfStores();
		  List<Contract> cons= getListOfContracts();
		  Flux<Order> orders = items.map(i ->{
			  if(i.getOverstockThreshold()==0) {
				  return autoOrders(i.getPrimaryKey().getId(),i.getPrimaryKey().getStoreId(),i.getQuantity(),highList,stores,cons);
			  }
			  return autoOrders(i.getPrimaryKey().getId(),i.getPrimaryKey().getStoreId(),i.getOverstockThreshold()-i.getQuantity(),highList,stores,cons);
		  });
		  Flux<Item> updatedInventory = orders.flatMap(o ->{
			ItemPrimaryKey key= new ItemPrimaryKey();
			key.setId(o.getItemId());
			key.setStoreId(o.getStoreId());
			System.out.println("haha");
			
			return itemRepo.findById(key).map(oldItem -> {
				oldItem.setQuantity(oldItem.getQuantity() + o.getQuantity());
				if(!o.getFromDistributor()) {
					removeItemsFromTransferStore(o);
					
				}
				return oldItem;
			});
			
		});
		return itemRepo.saveAll(updatedInventory);
		//return orders;
	}
	@Override
	public Flux<Item> removeItemsFromTransferStore(Order order){
		ItemPrimaryKey key= new ItemPrimaryKey();
		key.setId(order.getItemId());
		key.setStoreId(Integer.valueOf(order.getSupplier()));
		
		Mono<Item> updatedItem=itemRepo.findById(key).map(oldItem ->{
			oldItem.setQuantity(oldItem.getQuantity() - order.getQuantity());
			return oldItem;
		});

		
		return itemRepo.saveAll(updatedItem);
	}
}
