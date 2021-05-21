package com.revature.services;

import com.revature.beans.Item;
import com.revature.beans.Order;

import reactor.core.publisher.Flux;

public interface InventoryService {

	Flux<Item> autoOrder();
	Flux<Item> createManualOrder(Flux<Item> manualOrders);
	
	Flux<Item> removeItemsFromTransferStore(Order order);
	
}
