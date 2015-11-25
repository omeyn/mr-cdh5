package org.gbif.oliver.occurrence;

import org.gbif.dwc.terms.DwcTerm;
import org.gbif.dwc.terms.GbifInternalTerm;
import org.gbif.dwc.terms.GbifTerm;
import org.gbif.occurrence.common.identifier.HolyTriplet;
import org.gbif.occurrence.common.identifier.OccurrenceKeyHelper;
import org.gbif.occurrence.common.identifier.PublisherProvidedUniqueIdentifier;
import org.gbif.occurrence.persistence.hbase.Columns;

import java.io.IOException;
import java.util.UUID;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Populates the occurrence lookup table with lookups for each identifier found in the scan. Both tables need to exist!
 * Third parameter is optional and allows for scoping within a dataset (if absent does entire table).
 */
public class LookupPopulator {

  private static final byte[] OCC_CF = Columns.CF;
  private static final byte[] DK_COL = Bytes.toBytes(Columns.column(GbifTerm.datasetKey));
  private static final byte[] IC_COL = Bytes.toBytes(Columns.column(DwcTerm.institutionCode));
  private static final byte[] CC_COL = Bytes.toBytes(Columns.column(DwcTerm.collectionCode));
  private static final byte[] CN_COL = Bytes.toBytes(Columns.column(DwcTerm.catalogNumber));
  private static final byte[] UQ_COL = Bytes.toBytes(Columns.column(GbifInternalTerm.unitQualifier));
  private static final byte[] OCCID_COL = Bytes.toBytes(Columns.verbatimColumn(DwcTerm.occurrenceID));

  private static final byte[] ALLOCATED = Bytes.toBytes("ALLOCATED");

  private static final Logger LOG = LoggerFactory.getLogger(LookupPopulator.class);

  static class UniqueIdScanMapper extends TableMapper<ImmutableBytesWritable, Put> {

    private int numRecords = 0;

    @Override
    public void map(ImmutableBytesWritable row, Result values, Context context) throws IOException {
      // we want to pass along occurrence key, dataset key, institution code, collection code, catalog number, uq
      ImmutableBytesWritable occurrenceKey = new ImmutableBytesWritable(row.get());

      String datasetKey = Bytes.toString(values.getValue(OCC_CF, DK_COL));
      String ic = Bytes.toString(values.getValue(OCC_CF, IC_COL));
      String cc = Bytes.toString(values.getValue(OCC_CF, CC_COL));
      String cn = Bytes.toString(values.getValue(OCC_CF, CN_COL));
      String uq = values.getValue(OCC_CF, UQ_COL) == null ? null : Bytes.toString(values.getValue(OCC_CF, UQ_COL));
      String occId =
        values.getValue(OCC_CF, OCCID_COL) == null ? null : Bytes.toString(values.getValue(OCC_CF, OCCID_COL));

      String tripletKey = null;
      try {
        tripletKey = null;
        tripletKey = OccurrenceKeyHelper.buildKey(new HolyTriplet(UUID.fromString(datasetKey), ic, cc, cn, uq));
      } catch (IllegalArgumentException e) {
        Integer key = Bytes.toInt(row.get());
        context.setStatus("Bad triplet for row [" + key + "] because [" + e.getMessage() + "]");
        LOG.warn("Bad triplet for row [{}] because [{}]", key, e.getMessage());
      }
      if (tripletKey != null) {
        Put put = new Put(Bytes.toBytes(tripletKey));
        put.add(Bytes.toBytes(Columns.OCCURRENCE_COLUMN_FAMILY), Bytes.toBytes(Columns.LOOKUP_KEY_COLUMN),
          occurrenceKey.get());
        put.add(Bytes.toBytes(Columns.OCCURRENCE_COLUMN_FAMILY), Bytes.toBytes(Columns.LOOKUP_STATUS_COLUMN), ALLOCATED);
        try {
          context.write(occurrenceKey, put);
        } catch (InterruptedException e) {
          throw new IOException(e);
        }
      }

      if (occId != null) {
        String occIdKey =
          OccurrenceKeyHelper.buildKey(new PublisherProvidedUniqueIdentifier(UUID.fromString(datasetKey), occId));
        Put put = new Put(Bytes.toBytes(occIdKey));
        put.add(Bytes.toBytes(Columns.OCCURRENCE_COLUMN_FAMILY), Bytes.toBytes(Columns.LOOKUP_KEY_COLUMN),
          occurrenceKey.get());
        put.add(Bytes.toBytes(Columns.OCCURRENCE_COLUMN_FAMILY), Bytes.toBytes(Columns.LOOKUP_STATUS_COLUMN), ALLOCATED);
        try {
          context.write(occurrenceKey, put);
        } catch (InterruptedException e) {
          throw new IOException(e);
        }
      }
      numRecords++;
      if (numRecords % 10000 == 0) {
        context.setStatus("mapper processed " + numRecords + " records so far");
      }
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.out.println("Usage: LookupPopulator <occurrenceTable> <lookupTable> <optional datasetKey>");
      System.exit(1);
    }
    String occurrenceTable = args[0];
    String lookupTable = args[1];
    String datasetKey = args[2];
    Configuration conf = HBaseConfiguration.create();
    conf.set("hbase.client.scanner.timeout.period", "600000");
    conf.set("hbase.regionserver.lease.period", "600000");
    conf.set("hbase.rpc.timeout", "600000");

    Job job = new Job(conf, "LookupPopulator - " + occurrenceTable);
    job.setJarByClass(LookupPopulator.class);
    Scan scan = new Scan();
    scan.addColumn(OCC_CF, DK_COL);
    scan.addColumn(OCC_CF, IC_COL);
    scan.addColumn(OCC_CF, CC_COL);
    scan.addColumn(OCC_CF, CN_COL);
    scan.addColumn(OCC_CF, UQ_COL);
    scan.addColumn(OCC_CF, OCCID_COL);
    scan.setCacheBlocks(false);
    scan.setCaching(200);
    if (datasetKey != null) {
      scan.setFilter(
        new SingleColumnValueFilter(OCC_CF, DK_COL, CompareFilter.CompareOp.EQUAL, Bytes.toBytes(datasetKey)));
    }

    job.setMapOutputValueClass(Put.class);
    job.setNumReduceTasks(40);
    job.getConfiguration().set("mapred.map.tasks.speculative.execution", "false");
    job.getConfiguration().set("mapred.reduce.tasks.speculative.execution", "false");

    TableMapReduceUtil.initTableMapperJob(occurrenceTable, scan, UniqueIdScanMapper.class, null, null, job);
    TableMapReduceUtil.initTableReducerJob(lookupTable, null, job);
    System.exit(job.waitForCompletion(true) ? 0 : 1);
  }
}

