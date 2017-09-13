package co.cask.plugin;

import co.cask.cdap.api.annotation.Description;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.cdap.api.data.batch.Input;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.lib.KeyValue;
import co.cask.cdap.api.plugin.PluginProperties;
import co.cask.cdap.etl.api.Emitter;
import co.cask.cdap.etl.api.PipelineConfigurer;
import co.cask.cdap.etl.api.action.SettableArguments;
import co.cask.cdap.etl.api.batch.BatchSource;
import co.cask.cdap.etl.api.batch.BatchSourceContext;
import co.cask.hydrator.common.SourceInputFormatProvider;
import co.cask.plugin.format.MultiTableConf;
import co.cask.plugin.format.MultiTableDBInputFormat;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Driver;
import java.util.Map;

/**
 * Batch source to read from multiple tables in a database using JDBC.
 */
@Plugin(type = BatchSource.PLUGIN_TYPE)
@Name("MultiTableDatabase")
@Description("Reads from multiple tables in a relational database. " +
  "Outputs one record for each row in each table, with the table name as a record field. " +
  "Also sets a pipeline argument for each table read, which contains the table schema. ")
public class MultiTableDBSource extends BatchSource<NullWritable, StructuredRecord, StructuredRecord> {
  private static final Logger LOG = LoggerFactory.getLogger(MultiTableDBSource.class);
  private static final String JDBC_PLUGIN_ID = "jdbc.driver";

  private final MultiTableConf conf;

  public MultiTableDBSource(MultiTableConf conf) {
    this.conf = conf;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) {
    Class<? extends Driver> jdbcDriverClass = pipelineConfigurer.usePluginClass("jdbc", conf.getJdbcPluginName(),
                                                                                JDBC_PLUGIN_ID,
                                                                                PluginProperties.builder().build());
    if (jdbcDriverClass == null) {
      throw new IllegalArgumentException(
        String.format("Unable to load JDBC Driver class for plugin name '%s'. " +
                        "Please make sure that the driver plugin has been installed correctly.",
                      conf.getJdbcPluginName()));
    }
    pipelineConfigurer.getStageConfigurer().setOutputSchema(null);
  }

  @Override
  public void prepareRun(BatchSourceContext context) throws Exception {
    Configuration hConf = new Configuration();
    Class<? extends Driver> driverClass = context.loadPluginClass(JDBC_PLUGIN_ID);
    Map<String, Schema> tables = MultiTableDBInputFormat.setInput(hConf, conf, driverClass);
    SettableArguments arguments = context.getArguments();
    for (Map.Entry<String, Schema> tableInfo : tables.entrySet()) {
      arguments.set(DynamicMultiFilesetSink.TABLE_PREFIX + tableInfo.getKey(), tableInfo.getValue().toString());
    }
    context.setInput(Input.of(conf.getReferenceName(),
                              new SourceInputFormatProvider(MultiTableDBInputFormat.class, hConf)));
  }

  @Override
  public void transform(KeyValue<NullWritable, StructuredRecord> input,
                        Emitter<StructuredRecord> emitter) throws Exception {
    emitter.emit(input.getValue());
  }
}