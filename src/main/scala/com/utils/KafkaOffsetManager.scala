package com.utils

import kafka.common.TopicAndPartition
import kafka.utils.ZkUtils
import org.I0Itec.zkclient.ZkClient
import org.apache.log4j.LogManager
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.kafka.HasOffsetRanges

/**
  * 负责kafka偏移量的读取和保存
  * Created by fc.w on 2018/05/30
  */
object KafkaOffsetManager {

  lazy val log = LogManager.getLogger(KafkaOffsetManager.getClass)

  /**
    * 读取zk里面的偏移量，如果有就返回对应的分区和偏移量
    * 如果没有就返回None
    * @param zkClient zk连接的client
    * @param zkOffsetPath 偏移量路径
    * @param topic topic名字
    * @return 偏移量Map or None
    */
  def readOffsets(zkClient: ZkClient, zkOffsetPath: String, topic: String): Option[Map[TopicAndPartition, Long]] = {
    //（偏移量字符串,zk元数据)
    val (offsetsRangesStrOpt, _) = ZkUtils.readDataMaybeNull(zkClient, zkOffsetPath)
    offsetsRangesStrOpt match {
      case Some(offsetsRangesStr) =>
        // 获取这个topic在ZK里面最新的分区数量
        val lastest_partitions = ZkUtils.getPartitionsForTopics(zkClient,Seq(topic)).get(topic).get
        var offsets =  offsetsRangesStr.split(",") // 按逗号split成数组
          .map(s => s.split(":")) // 按冒号拆分每个分区和偏移量
          .map{case Array(partitionStr, offsetStr) => (TopicAndPartition(topic, partitionStr.toInt) -> offsetStr.toLong)}
          .toMap

        // 说明有分区扩展了
        if (offsets.size < lastest_partitions.size) {
          // 得到旧的所有分区序号
          val oldPartitions = offsets.keys.map(p => p.partition).toArray
          // 通过做差集得出来多的分区数量数组
          val addPartitions=lastest_partitions.diff(oldPartitions)
          if(addPartitions.size > 0){
            log.warn("发现kafka新增分区："+addPartitions.mkString(","))
            addPartitions.foreach(partitionId => {
              offsets += (TopicAndPartition(topic,partitionId) -> 0)
              log.warn("新增分区id："+partitionId+"添加完毕....")
            })
          }
        } else {
          log.warn("没有发现新增的kafka分区："+lastest_partitions.mkString(","))
        }

        Some(offsets)// 将Map返回
      case None =>
        None // 如果是null，就返回None

    }
  }

  /**
    * 保存每个批次的rdd的offset到zk中
    * @param zkClient zk连接的client
    * @param zkOffsetPath 偏移量路径
    * @param rdd 每个批次的rdd
    */
  def saveOffsets(zkClient: ZkClient, zkOffsetPath: String, rdd: RDD[_]): Unit = {
    // 转换rdd为Array[OffsetRange]
    val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
    // 转换每个OffsetRange为存储到zk时的字符串格式 :  分区序号1:偏移量1,  分区序号2:偏移量2,......
    val offsetsRangesStr = offsetRanges.map(offsetRange => s"${offsetRange.partition}:${offsetRange.untilOffset}").mkString(",")
    log.debug(" 保存的偏移量：  " + offsetsRangesStr)
    // 将最终的字符串结果保存到zk里面
    ZkUtils.updatePersistentPath(zkClient, zkOffsetPath, offsetsRangesStr)
  }


  class Stopwatch {
    private val start = System.currentTimeMillis()
    def get():Long = (System.currentTimeMillis() - start)
  }

}
