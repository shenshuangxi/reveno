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

package org.reveno.atp.core.disruptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.reveno.atp.api.Configuration.CpuConsumption;
import org.reveno.atp.api.EventsManager.EventMetadata;
import org.reveno.atp.api.commands.EmptyResult;
import org.reveno.atp.api.commands.Result;
import org.reveno.atp.core.api.TransactionCommitInfo;
import org.reveno.atp.core.api.RestoreableEventBus;
import org.reveno.atp.core.engine.processor.PipeProcessor;
import org.reveno.atp.core.engine.processor.ProcessorContext;
import org.reveno.atp.core.engine.processor.ProcessorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.PhasedBackoffWaitStrategy;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.EventHandlerGroup;
import com.lmax.disruptor.dsl.ProducerType;

public class DisruptorPipeProcessor implements PipeProcessor {

	public DisruptorPipeProcessor(CpuConsumption cpuConsumption,
			boolean singleProducer, Executor executor) {
		this.cpuConsumption = cpuConsumption;
		this.singleProducer = singleProducer;
		this.executor = executor;
	}

	@Override
	public void start() {
		if (isStarted) throw new IllegalStateException("The Pipe Processor is alredy started.");
		
		disruptor = new Disruptor<ProcessorContext>(eventFactory, 4 * 1024, executor,
				singleProducer ? ProducerType.SINGLE : ProducerType.MULTI,
				createWaitStrategy());

		attachHandlers(disruptor);
		// TODO exception listener that will stop disruptor, mark node Slave, etc.
		disruptor.start();

		log.info("Started.");
		isStarted = true;
	}

	@Override
	public void stop() {
		if (!isStarted) throw new IllegalStateException("The Pipe Processor is already stopped.");
		
		isStarted = false;
		disruptor.shutdown();
		log.info("Stopped.");
	}
	
	@Override
	public void sync() {
		requireStarted(()-> {
			final CompletableFuture<EmptyResult> f = new CompletableFuture<EmptyResult>();
			disruptor.publishEvent((e,s) -> e.reset().future(f).abort(null));
			try {
				f.get();
			} catch (Throwable t) {
				log.error("sync", t);
			}
			return null;
		});
	}
	
	@Override
	public void shutdown() {
		stop();
		disruptor.shutdown();
		
		for (int i = 0; i < 4 * 1024; i++)
			disruptor.getRingBuffer().get(i).destroy();
	}

	@Override
	public boolean isStarted() {
		return isStarted;
	}

	@Override
	public CompletableFuture<EmptyResult> process(List<Object> commands) {
		return requireStarted(() -> {
			final CompletableFuture<EmptyResult> f = new CompletableFuture<EmptyResult>();
			disruptor.publishEvent((e,s) -> e.reset().future(f).addCommands(commands));
			return f;
		});
	}

	@Override
	public <R> CompletableFuture<Result<? extends R>> execute(Object command) {
		return requireStarted(() -> {
			final CompletableFuture<Result<? extends R>> f = new CompletableFuture<Result<? extends R>>();
			disruptor.publishEvent((e,s) -> e.reset().future(f).addCommand(command).withResult());
			return f;
		});
	}
	
	@Override
	public void executeRestore(RestoreableEventBus eventBus, TransactionCommitInfo tx) {
		requireStarted(() -> {
			disruptor.publishEvent((e,s) -> e.reset().restore().transactionId(tx.getTransactionId())
					.eventBus(eventBus).eventMetadata(metadata(tx)).getTransactions()
					.addAll(Arrays.asList(tx.getTransactionCommits())));
			return null;
		});
	}
	
	@Override
	public PipeProcessor pipe(ProcessorHandler... handler) {
		if (!isStarted)
			handlers.add(handler);
		return this;
	}

	protected WaitStrategy createWaitStrategy() {
		switch (cpuConsumption) {
		case LOW:
			return new BlockingWaitStrategy();
		case NORMAL:
			return new SleepingWaitStrategy();
		case HIGH:
			return new YieldingWaitStrategy();
		case PHASED:
			return PhasedBackoffWaitStrategy.withLock((int) 2.5e5, (int) 8.5e5,
					TimeUnit.NANOSECONDS);
		}
		return null;
	}
	
	protected EventMetadata metadata(TransactionCommitInfo tx) {
		return new EventMetadata(true, tx.getTime());
	}
	
	protected void attachHandlers(Disruptor<ProcessorContext> disruptor) {
		List<EventHandler<ProcessorContext>[]> disruptorHandlers = handlers.stream()
				.<EventHandler<ProcessorContext>[]> map(this::convert)
				.collect(Collectors.toList());
		
		EventHandlerGroup<ProcessorContext> h = disruptor.handleEventsWith(disruptorHandlers.get(0));
		for (int i = 1; i < disruptorHandlers.size(); i++)
			h = h.then(disruptorHandlers.get(i));
	}

	<T> T requireStarted(Supplier<T> body) {
		if (isStarted)
			return body.get();
		else
			throw new IllegalStateException(
					"Pipe Processor must be started first.");
	}
	
	@SuppressWarnings("unchecked")
	protected EventHandler<ProcessorContext>[] convert(ProcessorHandler[] h) {
		EventHandler<ProcessorContext>[] acs = new EventHandler[h.length];
		for (int i = 0; i < h.length; i++) {
			final ProcessorHandler hh = h[i];
			acs[i] = (e, c, eob) -> hh.handle(e, eob);
		}	
		return acs;
	}

	protected volatile boolean isStarted = false;
	protected Disruptor<ProcessorContext> disruptor;
	protected List<ProcessorHandler[]> handlers = new ArrayList<>();
	protected final boolean singleProducer;
	protected final CpuConsumption cpuConsumption;
	protected final Executor executor;
	protected static final EventFactory<ProcessorContext> eventFactory = () -> new ProcessorContext();
	private static final Logger log = LoggerFactory.getLogger(DisruptorPipeProcessor.class);
	
}
