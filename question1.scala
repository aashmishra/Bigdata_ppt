case class Data (name: String,Age : Int, Location: String )

val sample : List[Data]= List(
  Data("Rajesh", 21, "London"),
  Data("Suresh", 28, "California"),
  Data("Sam", 26, "Delhi"),
  Data("Rajesh", 21, "Gurgaon"),
  Data("Manish", 29, "Bengalur")
)

sample.map(x => ((x.name+x.Age)->x)).toMap.values.foreach(print)