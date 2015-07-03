import java.io.File

import com.github.tototoshi.csv.CSVWriter
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkContext, SparkConf}
import org.kuleuven.esat.graphicalModels.KernelizedModel
import org.kuleuven.esat.svm.LSSVMSparkModel

/**
 * Created by mandar on 1/7/15.
 */
object TestSUSY {
  def apply(nCores: Int = 4, prototypes: Int = 1, kernel: String,
            globalOptMethod: String = "gs", grid: Int = 7,
            step: Double = 0.3, logscale: Boolean = false, frac: Double): Unit = {

    val config = Map(
      "file" -> "/esat/smcdata/guests/mandar/susy.csv",
      "delim" -> ",",
      "head" -> "false",
      "task" -> "classification",
      "parallelism" -> nCores.toString
    )

    val configtest = Map("file" -> "/esat/smcdata/guests/mandar/susytest.csv",
      "delim" -> ",",
      "head" -> "false")

    val conf = new SparkConf().setAppName("SUSY").setMaster("local["+nCores+"]")

    conf.registerKryoClasses(Array(classOf[LSSVMSparkModel]))

    val sc = new SparkContext(conf)

    val model = LSSVMSparkModel(config, sc)

    val nProt = if (kernel == "Linear") {
      model.npoints.toInt
    } else {
      if(prototypes > 0)
        prototypes
      else
        math.sqrt(model.npoints.toDouble).toInt
    }

    model.setBatchFraction(frac)
    val (optModel, optConfig) = KernelizedModel.getOptimizedModel[RDD[(Long, LabeledPoint)],
      RDD[LabeledPoint], model.type](model, globalOptMethod,
        kernel, nProt, grid, step, logscale)

    model.setBatchFraction(1.0)

    optModel.setMaxIterations(2).learn()

    val met = optModel.evaluate(configtest)

    optModel.unpersist

    met.print()
    println("Optimal Configuration: "+optConfig)
    val scale = if(logscale) "log" else "linear"

    val perf = met.kpi()
    val row = Seq(kernel, prototypes.toString, globalOptMethod,
      grid.toString, step.toString, scale,
      perf(0), perf(1), perf(2), optConfig.toString)

    val writer = CSVWriter.open(new File("data/resultsSUSY.csv"), append = true)
    writer.writeRow(row)
    writer.close()

  }
}
