package org.gbif.oliver.occurrence;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.PrefixFilter;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.Job;

/**
 * Deletes lookups for the given dataset, leaving occurrences untouched.
 */
public class LookupDeleter {

  private LookupDeleter() {
    // never instantiated
  }

  static class LookupScanMapper extends TableMapper<ImmutableBytesWritable, Delete> {

    private int numRecords = 0;

    @Override
    public void map(ImmutableBytesWritable row, Result values, Context context) throws IOException {
      byte[] key = row.get();

      Delete del = new Delete(key);
      try {
        context.write(new ImmutableBytesWritable(key), del);
      } catch (InterruptedException e) {
        throw new IOException(e);
      }

      numRecords++;
      if (numRecords % 10000 == 0) {
        context.setStatus("mapper processed " + numRecords + " records so far");
      }
    }
  }

  public static void main(String[] args) throws IOException, ClassNotFoundException, InterruptedException {
    if (args.length != 2) {
      System.out.println("Usage: LookupDeleter <lookup table name> <dataset key>");
      System.exit(1);
    }
    String lookupTableName = args[0];
    String datasetKey = args[1];
    Configuration conf = HBaseConfiguration.create();
    conf.set("hbase.client.scanner.timeout.period", "600000");
    conf.set("hbase.regionserver.lease.period", "600000");
    conf.set("hbase.rpc.timeout", "600000");

    Job job = new Job(conf, "Lookup Deleter - " + datasetKey + " from " + lookupTableName);
    job.setJarByClass(LookupDeleter.class);
    Scan scan = new Scan();
    scan.setCacheBlocks(false);
    scan.setCaching(200);
    scan.setStartRow(Bytes.toBytes(datasetKey));
    scan.setFilter(new PrefixFilter(Bytes.toBytes(datasetKey)));
    job.getConfiguration().set("mapred.map.tasks.speculative.execution", "false");

    job.setMapOutputValueClass(Delete.class);
    job.setNumReduceTasks(40);
    job.getConfiguration().set("mapred.map.tasks.speculative.execution", "false");
    job.getConfiguration().set("mapred.reduce.tasks.speculative.execution", "false");

    TableMapReduceUtil.initTableMapperJob(lookupTableName, scan, LookupScanMapper.class, null, null, job);
    TableMapReduceUtil.initTableReducerJob(lookupTableName, null, job);

    System.exit(job.waitForCompletion(true) ? 0 : 1);
  }
}
