/*
 * Copyright (c) 2011-2013 GoPivotal, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.rx.action;

import org.reactivestreams.Subscriber;
import reactor.event.dispatch.Dispatcher;
import reactor.function.Consumer;
import reactor.function.Supplier;
import reactor.rx.StreamSubscription;
import reactor.util.Assert;

import javax.annotation.Nonnull;

/**
 * @author Stephane Maldini
 * @since 2.0
 */
public class ParallelAction<O> extends Action<O, Action<O, O>> {

	private final ParallelStream[] publishers;
	private final int              poolSize;
	private final Consumer<Integer> requestConsumer = new Consumer<Integer>() {
		@Override
		public void accept(Integer n) {
			try {
				int previous = pendingRequest;

				if ((pendingRequest += n) < 0) pendingRequest = Integer.MAX_VALUE;

				if (n > batchSize) {
					getSubscription().request(batchSize);
				} else if (previous <= batchSize) {
					getSubscription().request(n);
				}

			} catch (Throwable t) {
				doError(t);
			}
		}
	};

	private int roundRobinIndex = -1;

	@SuppressWarnings("unchecked")
	public ParallelAction(Dispatcher parentDispatcher,
	                      Supplier<Dispatcher> multiDispatcher,
	                      Integer poolSize) {
		super(parentDispatcher);
		Assert.state(poolSize > 0, "Must provide a strictly positive number of concurrent sub-streams (poolSize)");
		this.poolSize = poolSize;
		this.publishers = new ParallelStream[poolSize];
		for (int i = 0; i < poolSize; i++) {
			this.publishers[i] = new ParallelStream<O>(ParallelAction.this, multiDispatcher.get(), i);
		}
	}

	@Override
	protected void onRequest(int n) {
		dispatch(n, requestConsumer);
	}

	@Override
	public Action<O, Action<O, O>> prefetch(int elements) {
		super.prefetch(elements);
		int size = elements / poolSize;
		for (ParallelStream p : publishers) {
			p.prefetch(size);
		}
		return this;
	}

	@Override
	public <A, E extends Action<Action<O, O>, A>> E connect(@Nonnull E stream) {
		E action = super.connect(stream);
		action.prefetch(poolSize).env(environment);
		return action;
	}

	@Override
	@SuppressWarnings("unchecked")
	protected StreamSubscription<Action<O, O>> createSubscription(final Subscriber<Action<O, O>> subscriber) {
		return new StreamSubscription<Action<O, O>>(this, subscriber) {
			long cursor = 0l;

			@Override
			public void request(int elements) {
				int i = 0;
				while (i < poolSize && i < cursor) {
					i++;
				}

				while (i < elements && i < poolSize) {
					cursor++;
					subscriber.onNext(publishers[i]);
					i++;
				}

				if (i == poolSize) {
					subscriber.onComplete();
				}
			}
		};
	}

	public int getPoolSize() {
		return poolSize;
	}

	public ParallelStream[] getPublishers() {
		return publishers;
	}

	@Override
	@SuppressWarnings("unchecked")
	protected void doNext(final O ev) {
		if (++roundRobinIndex == poolSize) {
			roundRobinIndex = 0;
		}

		ParallelStream<O> publisher = publishers[roundRobinIndex];
		if (publisher == null) return;

		try {
			publisher.broadcastNext(ev);
		} catch (Throwable t) {
			publisher.broadcastError(t);
		}
	}


	@Override
	protected void doError(Throwable throwable) {
		super.doError(throwable);
		for (ParallelStream parallelStream : publishers) {
			parallelStream.broadcastError(throwable);
		}
	}

	@Override
	protected void doComplete() {
		super.doComplete();
		for (ParallelStream parallelStream : publishers) {
			parallelStream.broadcastComplete();
		}
	}

	static private class ParallelStream<O> extends Action<O, O> {
		final ParallelAction<O> parallelAction;
		final int               index;

		private ParallelStream(ParallelAction<O> parallelAction, Dispatcher dispatcher, int index) {
			super(dispatcher);
			this.parallelAction = parallelAction;
			this.index = index;
		}

		@Override
		protected StreamSubscription<O> createSubscription(Subscriber<O> subscriber) {
			return new StreamSubscription<O>(this, subscriber) {
				@Override
				public void request(int elements) {
					super.request(elements);
					parallelAction.onRequest(elements);
				}

				@Override
				public void cancel() {
					super.cancel();
					parallelAction.publishers[index] = null;
				}

			};
		}

		@Override
		public String toString() {
			return super.toString() + "{" + (index + 1) + "/" + parallelAction.poolSize + "}";
		}
	}
}