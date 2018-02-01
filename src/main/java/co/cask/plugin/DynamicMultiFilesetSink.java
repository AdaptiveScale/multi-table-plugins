/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.plugin;

import co.cask.cdap.api.annotation.Description;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.cdap.api.data.batch.Output;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.DatasetProperties;
import co.cask.cdap.api.dataset.lib.KeyValue;
import co.cask.cdap.api.dataset.lib.PartitionKey;
import co.cask.cdap.api.dataset.lib.PartitionedFileSet;
import co.cask.cdap.api.dataset.lib.PartitionedFileSetArguments;
import co.cask.cdap.api.dataset.lib.PartitionedFileSetProperties;
import co.cask.cdap.api.dataset.lib.Partitioning;
import co.cask.cdap.api.plugin.PluginConfig;
import co.cask.cdap.etl.api.Emitter;
import co.cask.cdap.etl.api.batch.BatchSink;
import co.cask.cdap.etl.api.batch.BatchSinkContext;
import co.cask.plugin.format.RecordFilterOutputFormat;
import org.apache.hadoop.io.NullWritable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Writes to multiple partitioned file sets.
 */
@Plugin(type = BatchSink.PLUGIN_TYPE)
@Name("DynamicMultiFileset")
@Description("Writes to multiple partitioned file sets. File sets are partitioned by an ingesttime field " +
  "that will be set to the logical start time of the pipeline run. The sink will write to the correct sink based " +
  "on the value of a split field. For example, if the split field is configured to be 'tablename', any record " +
  "with a 'tablename' field of 'xyz' will be written to file set 'xyz'. This plugin expects that the filesets " +
  "to write to will be present in the pipeline arguments. Each table to write to must have an argument where " +
  "the key is 'multisink.[name]' and the value is the schema for that fileset. Most of the time, " +
  "this plugin will be used with the MultiTableDatabase source, which will set those pipeline arguments.")
public class DynamicMultiFilesetSink extends BatchSink<StructuredRecord, NullWritable, StructuredRecord> {
  public static final String TABLE_PREFIX = "multisink.";

  private final Conf conf;

  public DynamicMultiFilesetSink(Conf conf) {
    this.conf = conf;
  }

  @Override
  public void prepareRun(BatchSinkContext context) throws Exception {
    long ingestTime = TimeUnit.SECONDS.convert(context.getLogicalStartTime(), TimeUnit.MILLISECONDS);
    for (Map.Entry<String, String> argument : context.getArguments()) {
      String key = argument.getKey();
      String val = argument.getValue();
      if (!key.startsWith(TABLE_PREFIX)) {
        continue;
      }
      String dbTableName = key.substring(TABLE_PREFIX.length());
      //dbTableName is of the form db:table
      String name = dbTableName.split(":")[1];
      Schema schema = Schema.parseJson(val);

      if (!context.datasetExists(name)) {
        DatasetProperties properties = PartitionedFileSetProperties.builder()
          .setPartitioning(Partitioning.builder().addLongField("ingesttime").build())
          .setExploreTableName(name)
          .setOutputFormat(RecordFilterOutputFormat.class)
          .setOutputProperty(RecordFilterOutputFormat.FILTER_FIELD, conf.splitField)
          .setOutputProperty(RecordFilterOutputFormat.PASS_VALUE, name)
          .setOutputProperty(RecordFilterOutputFormat.DELIMITER, conf.delimiter)
          .setEnableExploreOnCreate(true)
          .setExploreFormat("text")
          .setExploreSchema(HiveSchemaConverter.toHiveSchema(schema))
          .setExploreFormatProperty("delimiter", conf.delimiter)
          .build();
        context.createDataset(name, PartitionedFileSet.class.getName(), properties);
      }

      Map<String, String> outputArgs = new HashMap<>();
      PartitionKey partitionKey = PartitionKey.builder().addLongField("ingesttime", ingestTime).build();
      PartitionedFileSetArguments.setOutputPartitionKey(outputArgs, partitionKey);
      context.addOutput(Output.ofDataset(name, outputArgs));
    }
  }

  @Override
  public void transform(StructuredRecord input,
                        Emitter<KeyValue<NullWritable, StructuredRecord>> emitter) throws Exception {
    emitter.emit(new KeyValue<>(NullWritable.get(), input));
  }

  /**
   * Plugin configuration properties.
   */
  public static class Conf extends PluginConfig {
    @Nullable
    @Description("The name of the field that will be used to determine which fileset to write to. " +
      "Defaults to 'tablename'.")
    private String splitField;

    @Nullable
    @Description("The delimiter to use to separate record fields. Defaults to the tab character.")
    private String delimiter;

    public Conf() {
      splitField = "tablename";
      delimiter = "\t";
    }
  }
}
