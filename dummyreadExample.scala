package com.ara.core.format.dataframe

import com.typesafe.config.Config
import org.apache.spark.sql.{DataFrame, SQLContext, SparkSession}

trait dummyreadExample {

 val config: Config

  def readDataFrame(sqlContext: SQLContext): DataFrame
  def writeDataFrame(df: DataFrame): Unit
  def formatDataFrame(df: DataFrame): DataFrame=DataFrameReadWriteFormat.formatDataFrame(df, config)
  def addHeader(df: DataFrame):DataFrame=DataFrameReadWriteFormat.addHeader(df, config)
 def mergeSparkFiles(spark: SparkSession)=DataFrameReadWriteFormat.mergeSparkFiles(config, spark)
 def renameSparkFiles(spark: SparkSession)=DataFrameReadWriteFormat.renameSparkFiles(config, spark)
}


