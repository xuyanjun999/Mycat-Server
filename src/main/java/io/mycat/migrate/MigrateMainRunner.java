package io.mycat.migrate;

import com.alibaba.fastjson.JSON;
import io.mycat.MycatServer;
import io.mycat.backend.datasource.PhysicalDBPool;
import io.mycat.backend.datasource.PhysicalDatasource;
import io.mycat.config.model.DBHostConfig;
import io.mycat.util.ZKUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by magicdoom on 2016/12/8.
 */
public class MigrateMainRunner implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrateMainRunner.class);
    private String dataHost;
    private List<MigrateTask> migrateTaskList;

    public MigrateMainRunner(String dataHost, List<MigrateTask> migrateTaskList) {
        this.dataHost = dataHost;
        this.migrateTaskList = migrateTaskList;
    }

    @Override public void run() {
        AtomicInteger sucessTask=new AtomicInteger(0);
        for (MigrateTask migrateTask : migrateTaskList) {
            new MigrateDumpRunner(migrateTask,sucessTask).run();
        }
         //同一个dataHost的任务合并执行，避免过多流量浪费
        if(sucessTask.get()==migrateTaskList.size())
        {
             long binlogFileNum=-1;
            String binlogFile="";
             long pos=-1;
            for (MigrateTask migrateTask : migrateTaskList) {
                if(binlogFileNum==-1){
                    binlogFileNum=Integer.parseInt(migrateTask.getBinlogFile().substring(migrateTask.getBinlogFile().lastIndexOf(".")+1)) ;
                    binlogFile=migrateTask.getBinlogFile();
                    pos=migrateTask.getPos();
                }  else{
                   int tempBinlogFileNum=Integer.parseInt(migrateTask.getBinlogFile().substring(migrateTask.getBinlogFile().lastIndexOf(".")+1)) ;
                    if(tempBinlogFileNum<=binlogFileNum&&migrateTask.getPos()<=pos) {
                       binlogFileNum=tempBinlogFileNum;
                        binlogFile=migrateTask.getBinlogFile();
                        pos=migrateTask.getPos();
                    }
                }
            }

           //开始增量数据迁移
            PhysicalDBPool dbPool= MycatServer.getInstance().getConfig().getDataHosts().get(dataHost);
            PhysicalDatasource datasource = dbPool.getSources()[dbPool.getActivedIndex()];
            DBHostConfig config = datasource.getConfig();
            BinlogStream  stream=new BinlogStream(config.getUrl().substring(0,config.getUrl().indexOf(":")),config.getPort(),config.getUser(),config.getPassword());
            try {
                stream.setSlaveID(migrateTaskList.get(0).getSlaveId());
                stream.setBinglogFile(binlogFile);
                stream.setBinlogPos(pos);
                stream.setMigrateTaskList(migrateTaskList);
                stream.connect();

            } catch (IOException e) {
               LOGGER.error("error:",e);
            }

        }
    }


    private void pushMsgToZK(String rootZkPath,String child,int status,String msg) throws Exception {
        String path = rootZkPath + "/" + child;
        TaskStatus taskStatus=JSON.parseObject(ZKUtils.getConnection().getData().forPath(path),TaskStatus.class);
        taskStatus.setMsg(msg);
        taskStatus.setStatus(status);
        ZKUtils.getConnection().setData().forPath(path, JSON.toJSONBytes(taskStatus)) ;

    }
}
