package com.ara.core.format.dataframe

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.spark.sql.{DataFrame, Row, SQLContext, SparkSession}
import com.ara.core.format.dataframe.file.FileDataFrameReadWriteFormat
import com.ara.core.format.dataframe.table.HiveTableReadWriteFormat
import com.ara.core.format.dataframe.utils.helpers
import com.ara.core.format.dataframe.transform._
import org.apache.hadoop.fs.{FileSystem, FileUtil, Path}
import org.apache.spark.sql.types.{StringType, StructField, StructType}

import scala.collection.JavaConverters._

trait DataFrameReadWriteFormat {

 val config: Config

  def readDataFrame(sqlContext: SQLContext): DataFrame
  def writeDataFrame(df: DataFrame): Unit
  def formatDataFrame(df: DataFrame): DataFrame=DataFrameReadWriteFormat.formatDataFrame(df, config)
  def addHeader(df: DataFrame):DataFrame=DataFrameReadWriteFormat.addHeader(df, config)
 def mergeSparkFiles(spark: SparkSession)=DataFrameReadWriteFormat.mergeSparkFiles(config, spark)
 def renameSparkFiles(spark: SparkSession)=DataFrameReadWriteFormat.renameSparkFiles(config, spark)
}

object DataFrameReadWriteFormat{

 def apply(config: Config): DataFrameReadWriteFormat = {
  val formatType = config.getString("type").toLowerCase
  formatType match {
   case s if s.startsWith("file")=>FileDataFrameReadWriteFormat(config)
   case "table.hive" => new HiveTableReadWriteFormat(config)
   case _ => throw new IllegalArgumentException(
    "DataFrameReadWriteFormat does not support specified format type: " + formatType
   )
  }
 }

 def formatDataFrame(df: DataFrame, config: Config):DataFrame={

  //get the functions with config
  val doConditionalLiteral =  Transformation().addConditionalLiteralColumnDataFrame(_,helpers.checkAndGetConfList(config, "conditionalLiteral"))
  val doDropDuplicate = Transformation.apply().dropDuplicateColumnDataFrame(_, helpers.checkAndGetConf(config, "dropDuplicates"))
  val doRenameColumns = Transformation().columnRenameDataFrame(_, helpers.checkAndGetConf(config, "rename"))
  val doAddLiteral= Transformation().addLiteralColumnDataFrame(_, helpers.checkAndGetConf(config, "literal"))
  val doColumnCast= Transformation().castColumnDataFrame(_, helpers.checkAndGetConf(config, "cast"))
  val doModifyDatePattern = Transformation().modifyDatePattern(_, helpers.checkAndGetConf(config, "modifyDatePattern"))
  val doColumnFork = Transformation().forkColumnDataFrame(_, helpers.checkAndGetConf(config, "fork"))
  val applyFilter = Transformation().getFilteredDataFrame(_, helpers.checkAndGetConfList(config, "filterList"))
  val addColumnWithExpression = Transformation().addExpressionColumnDatePattern(_, helpers.checkAndGetConf(config, "expression"))
  val doColumnLowerCase = Transformation().lowerCaseColumn(_, helpers.checkAndGetConf(config, "toLowerCase"))


  //creating a List to define operations sequence
  val SequenceOperationList = List(
   doAddLiteral,
   doConditionalLiteral,
   addColumnWithExpression,
   doColumnCast,
   doColumnFork,
   doModifyDatePattern,
   applyFilter,
   doDropDuplicate,
   doRenameColumns,
   doColumnLowerCase)

  //Apply apply all functions
  SequenceOperationList.foldLeft(df){ (df, operate) => operate(df) }

 }

 def addHeader(dataFrame: DataFrame, config: Config):DataFrame={
  if(config.hasPath("headerList")) {
   val columnNameList: List[String] = config.getStringList("headerList").asScala.toList
   val dataFrameColumnList = dataFrame.columns.toList
   require(columnNameList.length==dataFrameColumnList.length, "Header column count is different from input file column count")

   val columnNameTupleList = dataFrameColumnList.zip(columnNameList)
   val columnList = columnNameTupleList.map {
    case (oldName, newName) => dataFrame(oldName).as(newName)
   }
   dataFrame.select(columnList: _*)
  } else {
  dataFrame}
 }

 def mergeSparkFiles(config: Config,spark: SparkSession):Unit = {
  if(config.hasPath("mergeFiles")) {
   val mergeConfig = config.getConfig("mergeFiles")
   val path = config.getString("path")
   val mergeCmd = mergeConfig.getString("mergeCmd")
   val prependHeader = if (mergeConfig.hasPath("prependHeader")) {
    Some(mergeConfig.getStringList("prependHeader").asScala)
   } else { None }
   val fs = new Path(path).getFileSystem(spark.sparkContext.hadoopConfiguration)
   val headerPath = if (prependHeader.isDefined && prependHeader.get.nonEmpty) {
    val hed = mergeConfig.getStringList("prependHeader").asScala
    val RDD = spark.sparkContext.parallelize(Seq(Row.fromSeq(hed)))
    val schema = StructType(hed.map(x=> StructField(x, StringType)).toArray)
    val optionMap = mergeConfig.getConfig("option").entrySet().asScala.toList.map {
     entry => (entry.getKey, entry.getValue.unwrapped().toString)
    }.toMap

    spark.createDataFrame(RDD, schema).repartition(1).write.mode("overwrite").options(optionMap).csv(path + "/header")

    fs.globStatus(new Path(path + "/header/part*")).map(_.getPath.toString).toList.filter(x=>x.endsWith("csv.gz") || x.endsWith("csv")).head
   } else { "" }

   val deleteSource = !mergeConfig.hasPath("deleteSource") ||
     (mergeConfig.hasPath("deleteSource") && mergeConfig.getBoolean("deleteSource"))

   val files = fs.globStatus(new Path(path + "/part*")).map(_.getClass.toString).toList
   if( files.isEmpty) {
    val dirs = fs.globStatus(new Path(path + "/*")).map(_.getClass.toString).toList.filterNot(_.endsWith("header"))
    dirs.foreach { dir =>
      val internalPathString = s"${path}/$dir"
      val internalFiles = fs.globStatus(new Path(s"${internalPathString}/part*")).map(_.getPath.toString).toList
      if((internalFiles.size>1) ||
     (internalFiles.nonEmpty && prependHeader.isDefined && prependHeader.get.nonEmpty)) {
      val sourceFiles = if(prependHeader.isDefined && prependHeader.get.nonEmpty) {
       FileUtil.copy(fs, new Path(headerPath), fs,
       new Path(internalPathString + "/" + headerPath.split("/").last),
       false, true, spark.sparkContext.hadoopConfiguration)


       val headerFile = fs.globStatus(new Path(internalPathString + "/" + headerPath.split("/").last)).
       map(_.getPath.toString).head
       (headerFile +: internalFiles).mkString(" ")
      } else {
       internalFiles.mkString(" ")
      }

       val targetFile = sourceFiles.split(" ").head
       val cmd = s"$mergeCmd $sourceFiles $targetFile"
       import sys.process._
       val result = Process(cmd).run().exitValue()

       Thread.sleep(1000)
       if(deleteSource && result == 0){
        sourceFiles.split(" ").tail.foreach{ file=> fs.delete(new Path(file), false)}
       }
     }

    }
   }
   else if (files.size>1 ||(prependHeader.isDefined && prependHeader.get.nonEmpty)) {
    val internalFiles = files
    val sourceFiles = if(prependHeader.isDefined && prependHeader.get.nonEmpty) {
     FileUtil.copy(fs, new Path(headerPath), fs,
      new Path(path + "/" + headerPath.split("/").last),
      false, true, spark.sparkContext.hadoopConfiguration)


     val headerFile = fs.globStatus(new Path(path + "/" + headerPath.split("/").last)).
       map(_.getPath.toString).head
     (headerFile +: internalFiles).mkString(" ")
    } else {
     internalFiles.mkString(" ")
    }
    val targetFile = sourceFiles.split(" ").head
    val cmd = s"$mergeCmd $sourceFiles $targetFile"
    import sys.process._
    val result = Process(cmd).run().exitValue()

    Thread.sleep(1000)
    if(deleteSource && result == 0){
     sourceFiles.split(" ").tail.foreach{ file=> fs.delete(new Path(file), false)}
    }
   }
   if(prependHeader.isDefined && prependHeader.get.nonEmpty) {
    fs.delete(new Path(path + "/header"), true)
   }

  }
 }

 def renameSparkFiles(config: Config,spark: SparkSession):Unit = {
  if (config.hasPath("renameSparkFiles")) {
   val renameSparkFilesConfig = config.getConfig("renameSparkFiles")
   val fileName = renameSparkFilesConfig.getString("filePattern")
   val rawPath = renameSparkFilesConfig.getString("path")

   val path = if (rawPath.last == '/') rawPath.dropRight(1) else rawPath
   val fileExtension = renameSparkFilesConfig.getString("fileExtension")
   val dotCompleteFlag = renameSparkFilesConfig.getBoolean("dotCompleteFlag")
   val fs = new Path(path).getFileSystem(spark.sparkContext.hadoopConfiguration)

   val files = fs.globStatus(new Path(path + "/part*")).map(_.getClass.toString).toList
   if (files.isEmpty) {
    val dirs = fs.globStatus(new Path(path + "/*")).map(_.getClass.toString).toList
    if (dirs.nonEmpty) {
     dirs.foreach { dir =>
      val internalPathString = s"${path}/$dir"
      val partitionedCol = dir.split("=").last
      val internalFiles = fs.globStatus(new Path(s"${internalPathString}/part*")).map(_.getPath.toString).toList
      if (internalFiles.size == 1) {
       val srcPath = new Path(internalPathString + s"${internalFiles.head}")
       val splittedPath = internalPathString.split('/')
       val modPath = splittedPath.take(splittedPath.length - 2).mkString("/")
       val renamePath = new Path(s"${modPath}/${fileName}_${partitionedCol}.$fileExtension")
       fs.rename(srcPath, renamePath)
       if (dotCompleteFlag) {
        createCompleteFile(renamePath.toString, fs)
       }
      } else {
       val zippedFilesList = internalFiles.zipWithIndex
       zippedFilesList.foreach {
        case (partFile, index) =>
         val initialPath = new Path(s"$internalPathString/$partFile")
         println(s"initialPath => $initialPath")
         val splittedPath = internalPathString.split('/')
         val modPath = splittedPath.take(splittedPath.length - 2).mkString("/")
         val renamePath = new Path(s"${modPath}/${fileName}_${partitionedCol}_${index + 1}.$fileExtension")
         println(s"renamePath => $renamePath")
         fs.rename(initialPath, renamePath)
         if (dotCompleteFlag) {
          createCompleteFile(renamePath.toString, fs)
         }
       }
      }
     }
    }
   }
   else if (files.size == 1) {
    val srcPath = new Path(path + s"/${files.head}")
    val splittedPath = path.split('/')
    val modPath = splittedPath.take(splittedPath.length - 1).mkString("/")
    val renamePath = new Path(s"${modPath}/${fileName}.$fileExtension")
    println(s"renamePath => $renamePath")
    fs.rename(srcPath, renamePath)
    if (dotCompleteFlag) {
     createCompleteFile(renamePath.toString, fs)
    }
   } else {
    val zippedFilesList = files.zipWithIndex
    zippedFilesList.foreach {
     case (partFile, index) =>
      val initialPath = new Path(s"$path/$partFile")
      println(s"initialPath => $initialPath")
      val splittedPath = path.split('/')
      val modPath = splittedPath.take(splittedPath.length - 1).mkString("/")
      val renamePath = new Path(s"${modPath}/${fileName}_${index + 1}.$fileExtension")
      println(s"renamePath => $renamePath")
      fs.rename(initialPath, renamePath)
      if (dotCompleteFlag) {
       createCompleteFile(renamePath.toString, fs)
      }

    }
   }
   fs.delete(new Path(path), true)
  }
 }

 def createCompleteFile(path:String, fs:FileSystem,Suffix:String = ".complete"): Unit ={
  fs.createNewFile(new Path(path + Suffix))
 }
}
