import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.types.{DateType, StringType, StructField, StructType}
import org.apache.spark.sql.{Row, SparkSession}

import collection.JavaConverters._
import org.apache.spark.sql.functions._

object orchestrate {

def main(args: Array[String]): Unit = {
  val session = SparkSession.builder
    .master("local")
    .appName("clickuser")
    .enableHiveSupport()
    .getOrCreate()

  val destinationPath = args(0)
  val database = args(1)


  val sch = new StructType().add("timestamp", StringType).add("userid", StringType)

  val rdd = Seq(
    Row("2018-01-01 11:00:00", "u1"),
    Row("2018-01-01 11:10:00", "u1"),
    Row("2018-01-01 11:20:00", "u1"),
    Row("2018-01-01 13:50:00", "u1"),
    Row("2018-01-01 14:40:00", "u1"),
    Row("2018-01-01 15:30:00", "u1"),
    Row("2018-01-01 16:20:00", "u1"),
    Row("2018-01-01 16:50:00", "u1"),
    Row("2018-01-01 11:00:00", "u1"),
    Row("2018-01-01 12:10:00", "u2"),
    Row("2018-01-01 13:00:00", "u2"),
    Row("2018-01-01 13:50:00", "u2"),
    Row("2018-01-01 14:40:00", "u2"),
    Row("2018-01-01 15:30:00", "u2"),
    Row("2018-01-01 16:20:00", "u2"),
    Row("2018-01-01 16:50:00", "u2")
  ).asJava

  val sampleData = session.sqlContext.createDataFrame(rdd, sch)
//  sampleData.write.mode("overwrite").saveAsTable(database+".userdata")
//
//
//  val readData = session.sqlContext.table(database+".userdata")

  val user = sampleData.withColumn("normalizedTime", unix_timestamp(col("timestamp")))
  val w = Window.partitionBy(col("userid")).orderBy(col("timestamp").asc_nulls_first)
  val differ = user.withColumn("diff", col("normalizedTime") - lag(col("normalizedTime"), 1).over(w))
  val data = differ.withColumn("timeDiff", when(col("diff").isNull || col("diff") >= 1800, 0L).
    otherwise(col("diff")))

  def getSessionList() = udf { (userid: String, clickTimeList: Seq[String], tsList: Seq[Long]) =>
    def sid(n: Long) = s"$userid-$n"

    val sessionIdList = tsList.foldLeft((List[String](), 0L, 0L)) { case ((prevIdList, currentCum, currentId), diff) =>
      if (diff == 0 || currentCum + diff >= 7200) (sid(currentId + 1) :: prevIdList, 0L, currentId + 1) else
        (sid(currentId) :: prevIdList, currentCum + diff, currentId)
    }._1.reverse

    clickTimeList zip sessionIdList
  }

  val groupDF = data.
    groupBy("user" +
      "id").agg(
    collect_list(col("timestamp")).as("timestamp_list"), collect_list(col("timeDiff")).as("timeDiff_list")
  )


  val explodedDF = groupDF.withColumn("sessionid",
    explode(getSessionList()(col("userid"), col("timestamp_list"), col("timeDiff_list"))))

  val finalDF = explodedDF.select(col("userid"), col("sessionid._1").as("timestamp"), col("sessionid._2").as("usersessionid"))


val sessionperday=  finalDF.withColumn("datecolumn", col("timestamp").cast(DateType)).
    withColumn("sessionperday", size(collect_set("usersessionid").over(Window.partitionBy("datecolumn"))))


val  userinfoDF =differ.withColumn("datecolumn", col("timestamp").cast(DateType)).
    withColumn("usertimeperday", sum("diff").over(Window.partitionBy("datecolumn", "userid"))).
    withColumn("yearmonth", year(col("datecolumn"))*100+month(col("datecolumn"))).
    withColumn("usertimepermonth", sum("diff").over(Window.partitionBy("yearmonth", "userid")))

 val beforewriteDF = sessionperday.drop("datecolumn").join(userinfoDF,Seq("userid", "timestamp"),"inner").
   select("userid", "timestamp", "usersessionid", "sessionperday", "usertimeperday", "usertimepermonth",
   "yearmonth", col("datecolumn").toString())


  beforewriteDF.
  write.
    mode("overwrite").
   partitionBy("yearmonth", "datecolumn").
    parquet(destinationPath)
}








}
