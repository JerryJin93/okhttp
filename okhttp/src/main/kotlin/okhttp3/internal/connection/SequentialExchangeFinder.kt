/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.connection

import java.io.IOException

/** Attempt routes one at a time until one connects. */
internal class SequentialExchangeFinder(
  override val routePlanner: RoutePlanner,
) : ExchangeFinder {

  /**
   * 1. 找到合适的plan, 尽量复用的原因是建立TCP连接比较耗时
   *    1) 检测当前Call的connection, 如果可复用的话则用ReusePlan包装并返回，否则继续执行下一步
   *    2) 若不可复用，则去连接池(RealConnectionPool)寻找，若找到则用ReusePlan包装并返回，否则继续执行下一步
   *    3) 规划路径，创建ConnectPlan实例，并再次尝试从连接池中获取，由于连接路径规划成功后会产生一些了IP地址，再次从连接池中查找到概率大一些
   *    4) 若从连接池未获取到则返回创建的ConnectPlan实例，否则返回用ReusePlan包装的连接池中找到的connection
   * 2. 如果是新建的连接，则开始TCP连接(视情况判断是否需要TLS)，否则忽略
   * 3. 连接成功后缓存到连接池
   */
  override fun find(): RealConnection {
    var firstException: IOException? = null
    while (true) {
      if (routePlanner.isCanceled()) throw IOException("Canceled")

      try {
        val plan = routePlanner.plan()

        if (!plan.isReady) {
          // establish TCP connection.
          val tcpConnectResult = plan.connectTcp()
          // create RealConnection instance
          val connectResult =
            when {
              // TCP连接成功后进行SSL/TLS握手
              tcpConnectResult.isSuccess -> plan.connectTlsEtc()
              else -> tcpConnectResult
            }

          val (_, nextPlan, failure) = connectResult

          if (failure != null) throw failure
          if (nextPlan != null) {
            routePlanner.deferredPlans.addFirst(nextPlan)
            continue
          }
        }
        // put the RealConnection instance into the RealConnectionPool
        return plan.handleSuccess()
      } catch (e: IOException) {
        if (firstException == null) {
          firstException = e
        } else {
          firstException.addSuppressed(e)
        }
        if (!routePlanner.hasNext()) {
          throw firstException
        }
      }
    }
  }
}
