/** 
 *  Copyright (c) 2015 The original author or authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0

 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.reveno.atp.acceptance.tests;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.reveno.atp.acceptance.api.commands.CreateNewAccountCommand;
import org.reveno.atp.acceptance.api.commands.NewOrderCommand;
import org.reveno.atp.acceptance.api.transactions.AcceptOrder;
import org.reveno.atp.acceptance.api.transactions.CreateAccount;
import org.reveno.atp.acceptance.api.transactions.Credit;
import org.reveno.atp.acceptance.api.transactions.Debit;
import org.reveno.atp.acceptance.handlers.Commands;
import org.reveno.atp.acceptance.handlers.Transactions;
import org.reveno.atp.acceptance.model.Account;
import org.reveno.atp.acceptance.model.Order;
import org.reveno.atp.acceptance.model.immutable.ImmutableAccount;
import org.reveno.atp.acceptance.model.immutable.ImmutableOrder;
import org.reveno.atp.acceptance.model.mutable.MutableAccount;
import org.reveno.atp.acceptance.model.mutable.MutableOrder;
import org.reveno.atp.acceptance.views.AccountView;
import org.reveno.atp.acceptance.views.OrderView;
import org.reveno.atp.api.Configuration.CpuConsumption;
import org.reveno.atp.api.Configuration.ModelType;
import org.reveno.atp.api.Reveno;
import org.reveno.atp.api.commands.Result;
import org.reveno.atp.core.Engine;
import org.reveno.atp.test.utils.FileUtils;

import com.google.common.io.Files;

@RunWith(Parameterized.class)
public class RevenoBaseTest {
	
	@Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                 { ModelType.IMMUTABLE }, { ModelType.MUTABLE }  
           });
    }
    
    @Parameter
    public ModelType modelType;
	
	protected File tempDir;
	
	@Before
	public void setUp() {
		tempDir = Files.createTempDir();
	}
	
	@After
	public void tearDown() throws IOException {
		FileUtils.delete(tempDir);
	}
	
	@Test
	public void mockTest() {
		// gradle requires such hack though
	}
	
	protected Reveno createEngine() {
		return createEngine((r) -> {});
	}

	protected Reveno createEngine(Consumer<Reveno> interceptor) {
		Reveno reveno = new Engine(tempDir);
		
		reveno.config().cpuConsumption(CpuConsumption.PHASED);
		reveno.config().modelType(modelType);
		
		reveno.domain().command(CreateNewAccountCommand.class, Long.class, Commands::createAccount);
		reveno.domain().command(NewOrderCommand.class, Long.class, Commands::newOrder);
		reveno.domain().transactionAction(CreateAccount.class, Transactions::createAccount);
		reveno.domain().transactionAction(AcceptOrder.class, Transactions::acceptOrder);
		reveno.domain().transactionAction(Credit.class, Transactions::credit);
		reveno.domain().transactionAction(Debit.class, Transactions::debit);
		
		reveno.domain().viewMapper(Account.class, AccountView.class, (e, ov, r) -> {
			return new AccountView(e.id(), e.currency(), e.balance(), e.orders(), reveno.query());
		});
		reveno.domain().viewMapper(Order.class, OrderView.class, (e, ov, r) -> {
			return new OrderView(e.id(), e.accountId(), e.size(), e.price(), e.time(), e.positionId(),
					e.symbol(), e.orderStatus(), e.orderType(), reveno.query());
		});
		
		interceptor.accept(reveno);
		
		if (modelType == ModelType.IMMUTABLE) {
			Transactions.accountFactory = ImmutableAccount.FACTORY;
			Transactions.orderFactory = ImmutableOrder.FACTORY;
		} else {
			Transactions.accountFactory = MutableAccount.FACTORY;
			Transactions.orderFactory = MutableOrder.FACTORY;
		}
		
		return reveno;
	}
	
	@SuppressWarnings("unchecked")
	protected <T> T sendCommandSync(Reveno reveno, Object command) throws InterruptedException, ExecutionException {
		Result<T> result = (Result<T>) reveno.executeCommand(command).get();
		if (!result.isSuccess())
			throw new RuntimeException(result.getException());
		return result.getResult();
	}
	
	protected void sendCommandsBatch(Reveno reveno, Object command, int n) throws InterruptedException, ExecutionException {
		for (int i = 0; i < n - 1; i++) {
			reveno.executeCommand(command);
		}
		sendCommandSync(reveno, command);
	}
	
	protected void sendCommandsBatch(Reveno reveno, List<? extends Object> commands) throws InterruptedException, ExecutionException {
		for (int i = 0; i < commands.size() - 1; i++) {
			reveno.executeCommand(commands.get(i));
		}
		sendCommandSync(reveno, commands.get(commands.size() - 1));
	}
	
	protected <T> Waiter listenFor(Reveno reveno, Class<T> event) {
		Waiter waiter = new Waiter(1);
		reveno.events().eventHandler(event, (e,m) -> waiter.countDown());
		return waiter;
	}
	
	protected <T> Waiter listenFor(Reveno reveno, Class<T> event, int n) {
		Waiter waiter = new Waiter(n);
		reveno.events().eventHandler(event, (e,m) -> waiter.countDown());
		return waiter;
	}
	
	public static class Waiter extends CountDownLatch {

		public Waiter(int count) {
			super(count);
		}
		
		public boolean isArrived() throws InterruptedException {
			return await(100, TimeUnit.MILLISECONDS);
		}
		
	}
	
}
