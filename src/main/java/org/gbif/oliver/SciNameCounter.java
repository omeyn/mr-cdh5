package org.gbif.oliver;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;

public class SciNameCounter {

  private SciNameCounter() {
  }

  static class ScanMapper extends TableMapper<ImmutableBytesWritable, NullWritable> {

    public enum Counters {ROWS}

    @Override
    public void map(ImmutableBytesWritable row, Result values, Mapper.Context context) throws IOException {
      context.getCounter(Counters.ROWS).increment(1);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.out.println("Usage SciNameCounter <table_name> <scientific_name>");
      System.exit(1);
    }
    String tableName = args[0];
    String sciName = args[1];

    Configuration conf = HBaseConfiguration.create();
    conf.set("hbase.client.scanner.timeout.period", "600000");
    conf.set("hbase.regionserver.lease.period", "600000");
    conf.set("hbase.rpc.timeout", "600000");

    Job job = new Job(conf, "SciNameCounter of [" + sciName + "]");
    job.setJarByClass(SciNameCounter.class);
    job.setOutputFormatClass(NullOutputFormat.class);
    job.setNumReduceTasks(0);
    job.getConfiguration().set("mapred.map.tasks.speculative.execution", "false");
    job.getConfiguration().set("mapred.reduce.tasks.speculative.execution", "false");

    Scan scan = buildScan(sciName);

    TableMapReduceUtil
      .initTableMapperJob(tableName, scan, ScanMapper.class, ImmutableBytesWritable.class, NullWritable.class, job);
    System.exit(job.waitForCompletion(true) ? 0 : 1);
  }

  private static Scan buildScan(String sciName) {
    Scan scan = new Scan();
    scan.setCaching(200);
    scan.setCacheBlocks(false);
    byte[] colFam = Bytes.toBytes("o");
    byte[] interpSciName = Bytes.toBytes("scientificName");
    scan.addColumn(colFam, interpSciName);

    SingleColumnValueFilter filter =
      new SingleColumnValueFilter(colFam, interpSciName, CompareFilter.CompareOp.EQUAL, Bytes.toBytes(sciName));
    filter.setFilterIfMissing(true);
    scan.setFilter(filter);

    return scan;
  }
}
