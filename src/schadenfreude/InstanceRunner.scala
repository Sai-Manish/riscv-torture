package torture
package schadenfreude

import scala.sys.process._
import scalax.file.Path
import scalax.file.FileSystem
import java.io.File
import java.io.FileWriter

object InstanceRunner
{
  def apply(insttype: String, instnum: Int, mgr: InstanceManager): InstanceRunner = 
  {
    assert(List("local","psi").contains(insttype), println("Invalid instance type specified."))
    val runner: InstanceRunner = insttype match {
      case "local" => new LocalRunner(instnum, mgr)
      case "psi" => new PSIRunner(instnum, mgr)
      case "ec2" => new EC2Runner(instnum, mgr)
    }
    return runner
  }
}

abstract class InstanceRunner
{
  val instancenum: Int
  var fileLogger = ProcessLogger(line => (), line => ())
  var locallogtime: Long = 0L
  val mgr: InstanceManager
  val fileop = overnight.FileOperations

  def copyTortureDir(tortureDir: String, instDir: String, config: String): Unit
  def run(cmdstr: String, workDir: String): Process
  def isDone(): Boolean
  def createLogger(logtime: Long): Unit = //Maybe move processlogger creation to instantiation
  {
    val logname = "output/schad" + instancenum + "_" + logtime + ".log"
    val logfile = new File(logname)
    logfile.createNewFile() //Ensure that the file exists.
    val plog = ProcessLogger(line => writeln(line, logname), line => writeln(line, logname))
    fileLogger = plog
    locallogtime = logtime
    println("Instance log output will be placed in " + (new File(logname)).getCanonicalPath())
  }
  def writeln(line: String, logfile: String): Unit =
  {
    val writer = new FileWriter(logfile, true)
    try {
      writer.write(line + "\n")
    } finally {
      writer.close()
    }
  }
}

class EC2Runner(val instancenum: Int, val mgr: InstanceManager) extends InstanceRunner
{
  def copyTortureDir(tortureDir: String, instDir: String, config: String): Unit =
  {

  }
  
  def run(cmdstr: String, workDir: String): Process =
  {
    Process("ls").run
  }

  def isDone(): Boolean =
  {
    true
  }
}

class LocalRunner(val instancenum: Int, val mgr: InstanceManager) extends InstanceRunner
{
  def copyTortureDir(tortureDir: String, instDir: String, config: String): Unit =
  {
    val torturePath: Path = tortureDir
    val instPath: Path = instDir
    val cfgPath: Path = config
    println("Copying torture directory to: " + instPath.normalize.path)
    if (instPath.isDirectory)
    {
      println(instPath.normalize.path + " already exists. Not copying torture.")
    } else {
      fileop.copy(torturePath, instPath)
      println("Copied torture to " + instPath.normalize.path)
    }
    fileop.copy(cfgPath, instPath / Path("config"))
    println("Using config file: " + cfgPath.path)
    println(" Cleaning up " + (instPath / Path("output")).normalize.path + " before running.")
    Process("make clean-all", new File(instDir + "/output")).!
  }

  def run(cmdstr: String, workDir: String): Process = 
  {
    val workDirFile = new File(workDir)
    val cmd = Process(cmdstr, workDirFile)
    println(("Starting instance %d".format(instancenum)) + " in directory " + workDirFile.getCanonicalPath())
    println(cmdstr)
    
    val proc = cmd.run(fileLogger)
    println("Started running instance %d\n" format(instancenum))
    proc
  }

  def isDone(): Boolean =
  {
    val logfile = "output/schad" + instancenum + "_" + locallogtime + ".log"
    val grepcmd = "grep Leaving " + logfile  //grep for better term.
    val output = grepcmd.!!
    return (output != "")
  }
}

class PSIRunner(val instancenum: Int, val mgr: InstanceManager) extends InstanceRunner
{
  var sshval: String = ""
  var ssherr: String = ""
  def copyTortureDir(tortureDir: String, instDir: String, config: String): Unit =
  {
    val torturePath: Path = tortureDir
    val instPath: Path = instDir
    //Complete the psi.qsub script
    fileop.copy(torturePath / Path("partialpsi.qsub"),torturePath / Path("psi.qsub"))
    val writer = new FileWriter("psi.qsub", true)
    try {
      writer.write(mgr.cmdstrRA(instancenum))
    } finally {
      writer.close()
    }
    fileop.scp(torturePath, instPath, "psi")
    fileop.scp(torturePath / Path("psi.qsub"), instPath / Path("psi.qsub"), "psi")
    fileop.scp(torturePath / Path(config), instPath / Path("config"), "psi")
  }
  
  def run(cmdstr: String, workDir: String): Process =
  {
    println("Instance output log will be placed in remote PSI file " + workDir + "/schad" + instancenum + "_" + locallogtime + ".out")
    println("Instance error log will be placed in remote PSI file " + workDir + "/schad" + instancenum + "_" + locallogtime + ".err")
    val sshcmd = "ssh psi cd " + workDir + " ; " + qsub(workDir)
    println(("Starting instance %d".format(instancenum)) + " remotely in PSI directory " + workDir)
    println(sshcmd)
    val proc = sshcmd.run(fileLogger)
    println("Started running instance %d\n" format(instancenum))
    proc
  }

  def isDone(): Boolean =
  {
    assert (ssherr=="", println("Error in qsubbing."))
    val jobid = sshval.dropRight(28)
    var out = ""
    val exit = Process("ssh psi qstat " + jobid).!(ProcessLogger(line=> if (line!="") out=line, line=>if (line!="") out=line))
    return out.contains("Unknown Job Id")
  }
  
  override def createLogger(logtime: Long): Unit = //Maybe move processlogger creation to instantiation
  {
    val logname = "output/schad" + instancenum + "_" + logtime + ".log"
    val plog = ProcessLogger(line => { writeln(line, logname); sshval=line }, line => {writeln(line, logname); ssherr = line})
    fileLogger = plog
    locallogtime = logtime
    println("Instance log output will be placed in " + (new File(logname)).getCanonicalPath())
  }

  private def qsub(instDir: String): String = 
  {
    val logfile = "schad" + instancenum + "_" + locallogtime
    val wt = mgr.runtime * 2 // Extra time so it doesn't cut the test off before it finishes.
    val walltime = (wt/60) + ":" + (wt % 60) + ":00"
    val cput = walltime // Fine to have them the same?
    
    var qsubstr = "qsub -N schad" + instancenum + " -r n -e localhost:" + instDir + "/" + logfile + ".err"
    qsubstr += " -o localhost:" + instDir + "/" + logfile + ".out -q psi -l nodes=1:ppn=1 -l mem=1024m"
    qsubstr += " -l walltime=" + walltime + " -l cput=" + cput + " psi.qsub"
    qsubstr
  }
}
