/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.sqoop.hive;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.apache.hadoop.sqoop.SqoopOptions;
import org.apache.hadoop.sqoop.manager.ConnManager;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Date;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Creates (Hive-specific) SQL DDL statements to create tables to hold data
 * we're importing from another source.
 *
 * After we import the database into HDFS, we can inject it into Hive using
 * the CREATE TABLE and LOAD DATA INPATH statements generated by this object.
 */
public class TableDefWriter {

  public static final Log LOG = LogFactory.getLog(TableDefWriter.class.getName());

  private SqoopOptions options;
  private ConnManager connManager;
  private Configuration configuration;
  private String tableName;
  private boolean commentsEnabled;

  /**
   * Creates a new TableDefWriter to generate a Hive CREATE TABLE statement.
   * @param opts program-wide options
   * @param connMgr the connection manager used to describe the table.
   * @param table the name of the table to read.
   * @param config the Hadoop configuration to use to connect to the dfs
   * @param withComments if true, then tables will be created with a
   *        timestamp comment.
   */
  public TableDefWriter(final SqoopOptions opts, final ConnManager connMgr,
      final String table, final Configuration config, final boolean withComments) {
    this.options = opts;
    this.connManager = connMgr;
    this.tableName = table;
    this.configuration = config;
    this.commentsEnabled = withComments;
  }

  /**
   * @return the CREATE TABLE statement for the table to load into hive.
   */
  public String getCreateTableStmt() throws IOException {
    Map<String, Integer> columnTypes = connManager.getColumnTypes(tableName);

    String [] colNames = options.getColumns();
    if (null == colNames) {
      colNames = connManager.getColumnNames(tableName);
    }

    StringBuilder sb = new StringBuilder();

    sb.append("CREATE TABLE " + tableName + " ( ");

    boolean first = true;
    for (String col : colNames) {
      if (!first) {
        sb.append(", ");
      }

      first = false;

      Integer colType = columnTypes.get(col);
      String hiveColType = connManager.toHiveType(colType);
      if (null == hiveColType) {
        throw new IOException("Hive does not support the SQL type for column " + col);
      }

      sb.append(col + " " + hiveColType);

      if (HiveTypes.isHiveTypeImprovised(colType)) {
        LOG.warn("Column " + col + " had to be cast to a less precise type in Hive");
      }
    }

    sb.append(") ");

    if (commentsEnabled) {
      DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
      String curDateStr = dateFormat.format(new Date());
      sb.append("COMMENT 'Imported by sqoop on " + curDateStr + "' ");
    }

    sb.append("ROW FORMAT DELIMITED FIELDS TERMINATED BY '");
    sb.append(getHiveOctalCharCode((int) options.getOutputFieldDelim()));
    sb.append("' LINES TERMINATED BY '");
    sb.append(getHiveOctalCharCode((int) options.getOutputRecordDelim()));
    sb.append("' STORED AS TEXTFILE");

    LOG.debug("Create statement: " + sb.toString());
    return sb.toString();
  }

  private static final int DEFAULT_HDFS_PORT =
      org.apache.hadoop.hdfs.server.namenode.NameNode.DEFAULT_PORT;

  /**
   * @return the LOAD DATA statement to import the data in HDFS into hive
   */
  public String getLoadDataStmt() throws IOException { 
    String warehouseDir = options.getWarehouseDir();
    if (null == warehouseDir) {
      warehouseDir = "";
    } else if (!warehouseDir.endsWith(File.separator)) {
      warehouseDir = warehouseDir + File.separator;
    }

    String tablePath = warehouseDir + tableName;
    FileSystem fs = FileSystem.get(configuration);
    Path finalPath = new Path(tablePath).makeQualified(fs);
    String finalPathStr = finalPath.toString();
    if (finalPathStr.startsWith("hdfs://") && finalPathStr.indexOf(":", 7) == -1) {
      // Hadoop removed the port number from the fully-qualified URL.
      // We need to reinsert this or else Hive will complain.
      // Do this right before the third instance of the '/' character.
      int insertPoint = 0;
      for (int i = 0; i < 3; i++) {
        insertPoint = finalPathStr.indexOf("/", insertPoint + 1);
      }

      if (insertPoint == -1) {
        LOG.warn("Fully-qualified HDFS path does not contain a port.");
        LOG.warn("this may cause a Hive error.");
      } else {
        finalPathStr = finalPathStr.substring(0, insertPoint) + ":" + DEFAULT_HDFS_PORT
            + finalPathStr.substring(insertPoint, finalPathStr.length());
      }
    }

    StringBuilder sb = new StringBuilder();
    sb.append("LOAD DATA INPATH '");
    sb.append(finalPathStr);
    sb.append("' INTO TABLE ");
    sb.append(tableName);

    LOG.debug("Load statement: " + sb.toString());
    return sb.toString();
  }

  /**
   * Return a string identifying the character to use as a delimiter
   * in Hive, in octal representation.
   * Hive can specify delimiter characters in the form '\ooo' where
   * ooo is a three-digit octal number between 000 and 177. Values
   * may not be truncated ('\12' is wrong; '\012' is ok) nor may they
   * be zero-prefixed (e.g., '\0177' is wrong).
   *
   * @param charNum the character to use as a delimiter
   * @return a string of the form "\ooo" where ooo is an octal number
   * in [000, 177].
   * @throws IllegalArgumentException if charNum &gt;> 0177.
   */
  static String getHiveOctalCharCode(int charNum)
      throws IllegalArgumentException {
    if (charNum > 0177) {
      throw new IllegalArgumentException(
          "Character " + charNum + " is an out-of-range delimiter");
    }

    return String.format("\\%03o", charNum);
  }
}

