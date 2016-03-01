package com.walmart.gatling.commons;

/**
 * Created by ahailemichael on 8/20/15.
 */

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.LogOutputStream;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import akka.event.Logging;
import akka.event.LoggingAdapter;
import javafx.util.Pair;

/**
 * Created by ahailemichael on 8/17/15.
 */
public class ScriptExecutor extends WorkExecutor {

    private LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    private AgentConfig agentConfig;

    public ScriptExecutor(AgentConfig agentConfig){

        this.agentConfig = agentConfig;
    }

    @Override
    public void onReceive(Object message) {
        if (message instanceof Master.Job) {
            Master.Job job = (Master.Job) message;
            TaskEvent taskEvent = (TaskEvent)job.taskEvent;
            if (taskEvent.getRoleName().equalsIgnoreCase("script")) {
                CommandLine cmdLine = new CommandLine(agentConfig.getJob().getCommand());

                Map<String, Object> map = new HashMap<>();

                if (StringUtils.isNotEmpty(agentConfig.getJob().getMainClass()))
                    cmdLine.addArgument(agentConfig.getJob().getCpOrJar());

                map.put("path", new File(agentConfig.getJob().getJobArtifact(taskEvent.getJobName())));
                cmdLine.addArgument("${path}");

                if (!StringUtils.isEmpty(agentConfig.getJob().getMainClass())) {
                    cmdLine.addArgument(agentConfig.getJob().getMainClass());
                }
                //parameters come from the task event
                for (Pair<String, String> pair : taskEvent.getParameters()) {
                    cmdLine.addArgument(pair.getValue());
                }

                cmdLine.setSubstitutionMap(map);
                DefaultExecutor executor = new DefaultExecutor();
                executor.setExitValues(agentConfig.getJob().getExitValues());
                ExecuteWatchdog watchdog = new ExecuteWatchdog(ExecuteWatchdog.INFINITE_TIMEOUT);
                executor.setWatchdog(watchdog);
                executor.setWorkingDirectory(new File(agentConfig.getJob().getPath()));
                FileOutputStream outFile = null;
                FileOutputStream errorFile = null;
                try {
                    String outPath = agentConfig.getJob().getOutPath(taskEvent.getJobName(), job.jobId);
                    String errPath = agentConfig.getJob().getErrorPath(taskEvent.getJobName(), job.jobId);
                    //create the std and err files
                    outFile = FileUtils.openOutputStream(new File(outPath));
                    errorFile = FileUtils.openOutputStream(new File(errPath));

                    PumpStreamHandler psh = new PumpStreamHandler(new ExecLogHandler(outFile),new ExecLogHandler(errorFile));

                    executor.setStreamHandler(psh);
                    System.out.println(cmdLine);
                    int exitResult = executor.execute(cmdLine);
                    Worker.Result result = new Worker.Result(exitResult,agentConfig.getUrl(errPath),agentConfig.getUrl(outPath));
                    if(executor.isFailure(exitResult)){
                        log.info("Script Executor Failed, result: " +result.toString());
                        getSender().tell(new Worker.WorkFailed(result), getSelf());
                    }
                    else{
                        log.info("Script Executor Completed, result: " +result.toString());
                        getSender().tell(new Worker.WorkComplete(result), getSelf());
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }finally {
                    IOUtils.closeQuietly(outFile);
                    IOUtils.closeQuietly(errorFile);
                }

            }
            else{
                unhandled(message);
            }
        }
    }


    class ExecLogHandler extends LogOutputStream {
        private  FileOutputStream file;

        public ExecLogHandler(FileOutputStream file) {
            this.file = file;
        }

        @Override
        protected void processLine(String line, int level) {
            try {
                IOUtils.write(line, file);
            } catch (IOException e) {
                //e.printStackTrace();
            }
        }
    }
}