package org.apache.flink.streaming.connectors.eventhubs;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Counter;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.connectors.eventhubs.internals.EventhubProducerThread;
import org.apache.flink.streaming.connectors.eventhubs.internals.ProducerCache;
import org.apache.flink.streaming.util.serialization.SerializationSchema;
import org.apache.flink.util.Preconditions;

import com.microsoft.azure.eventhubs.EventData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Created by jozh on 6/20/2017.
 * Will support customize parttion in next version
 */
public class FlinkEventHubProducer<OUT> extends RichSinkFunction<OUT> implements CheckpointedFunction {

	private static final Logger logger = LoggerFactory.getLogger(FlinkEventHubProducer.class);
	private static final long serialVersionUID = -7486455932880508035L;
	private final SerializationSchema schema;
	private final ProducerCache cache;
	private final Properties eventhubsProps;
	private EventhubProducerThread producerThread;
	private Counter prepareSendCount;
	private Counter commitSendCount;

	public FlinkEventHubProducer(SerializationSchema<OUT> serializationSchema, Properties eventhubsProps){
		Preconditions.checkNotNull(serializationSchema);
		Preconditions.checkNotNull(eventhubsProps);
		Preconditions.checkNotNull(eventhubsProps.getProperty("eventhubs.policyname"));
		Preconditions.checkNotNull(eventhubsProps.getProperty("eventhubs.policykey"));
		Preconditions.checkNotNull(eventhubsProps.getProperty("eventhubs.namespace"));
		Preconditions.checkNotNull(eventhubsProps.getProperty("eventhubs.name"));

		this.schema = serializationSchema;
		this.eventhubsProps = eventhubsProps;

		int capacity = eventhubsProps.getProperty("eventhubs.cache.capacity") == null
			? ProducerCache.DEFAULTCAPACITY : Integer.parseInt(eventhubsProps.getProperty("eventhubs.cache.capacity"));

		long timeout = eventhubsProps.getProperty("eventhubs.cache.timeout") == null
			? ProducerCache.DEFAULTTIMEOUTMILLISECOND : Long.parseLong(eventhubsProps.getProperty("eventhubs.cache.timeout"));

		this.cache = new ProducerCache(capacity, timeout);

		logger.info("Created eventhub producer for namespace: {}, name: {}",
			eventhubsProps.getProperty("eventhubs.namespace"),
			eventhubsProps.getProperty("eventhubs.name"));
	}

	@Override
	public void snapshotState(FunctionSnapshotContext context) throws Exception {
		cache.checkErr();
	}

	@Override
	public void initializeState(FunctionInitializationContext context) throws Exception {

	}

	@Override
	public void invoke(OUT value) throws Exception {
		cache.checkErr();
		EventData event = new EventData(this.schema.serialize(value));
		cache.put(event);
		prepareSendCount.inc();
		logger.debug("Insert a event input output cache");
		cache.checkErr();
	}

	@Override
	public void open(Configuration parameters) throws Exception {
		super.open(parameters);
		prepareSendCount = getRuntimeContext().getMetricGroup().addGroup(this.getClass().getName()).counter("prepare_send_event_count");
		commitSendCount = getRuntimeContext().getMetricGroup().addGroup(this.getClass().getName()).counter("commit_send_event_count");
		String threadName = getEventhubProducerName();

		logger.info("Eventhub producer thread {} starting", threadName);
		producerThread = new EventhubProducerThread(
			logger,
			threadName,
			cache,
			eventhubsProps,
			commitSendCount);
		producerThread.start();
		logger.info("Eventhub producer thread {} started", threadName);
		cache.checkErr();
	}

	@Override
	public void close() throws Exception {
		super.close();
		logger.info("Eventhub producer thread close on demand");
		producerThread.shutdown();
		cache.close();
		cache.checkErr();
	}

	protected String getEventhubProducerName(){
		return "Eventhub producer " + getRuntimeContext().getTaskNameWithSubtasks();
	}
}
