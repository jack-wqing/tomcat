/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina;

/**
 * LifecycleState 枚举定义了实现 {@link Lifecycle} 接口的组件可能处于的所有合法状态。
 *
 * <h3>设计意图</h3>
 * <p>
 * Tomcat 是一个由大量组件构成的复杂系统（Server/Service/Engine/Host/Context/Wrapper 等），
 * 每个组件都有自己的初始化、启动、停止、销毁流程。为了保证组件状态的一致性和可预测性，
 * Tomcat 使用<strong>有限状态机（FSM）</strong>模型来管理组件生命周期：
 * <ul>
 *   <li>组件在任意时刻只能处于一个明确的状态</li>
 *   <li>状态转换必须遵循预定义的规则，非法转换会抛出异常</li>
 *   <li>每个状态都关联一个生命周期事件，供监听器感知状态变化</li>
 * </ul>
 * </p>
 *
 * <h3>字段说明</h3>
 * <table border="1">
 *   <tr><th>字段</th><th>类型</th><th>含义</th></tr>
 *   <tr>
 *     <td><code>available</code></td>
 *     <td>boolean</td>
 *     <td>
 *       标记组件是否"可用"——即除了属性 getter/setter 和生命周期方法之外，
 *       组件的业务方法是否可以被安全调用。
 *       <br>
 *       处于 STARTING、STARTED、STOPPING_PREP 状态时为 true（正在提供服务或即将停止服务），
 *       其他状态为 false。
 *     </td>
 *   </tr>
 *   <tr>
 *     <td><code>lifecycleEvent</code></td>
 *     <td>String</td>
 *     <td>
 *       进入该状态时触发的事件类型名称，对应 {@link Lifecycle} 中定义的事件常量。
 *       <br>
 *       例如：进入 INITIALIZING 状态触发 BEFORE_INIT_EVENT，进入 INITIALIZED 状态触发 AFTER_INIT_EVENT。
 *       <br>
 *       若为 null，则表示该状态没有对应的事件（如 NEW 和 FAILED）。
 *     </td>
 *   </tr>
 * </table>
 *
 * <h3>状态总览</h3>
 * <table border="1">
 *   <tr><th>状态</th><th>可用？</th><th>对应事件</th><th>说明</th></tr>
 *   <tr><td>NEW</td><td>否</td><td>无</td><td>对象刚创建，尚未初始化</td></tr>
 *   <tr><td>INITIALIZING</td><td>否</td><td>BEFORE_INIT_EVENT</td><td>正在初始化</td></tr>
 *   <tr><td>INITIALIZED</td><td>否</td><td>AFTER_INIT_EVENT</td><td>初始化完成，等待启动</td></tr>
 *   <tr><td>STARTING_PREP</td><td>否</td><td>BEFORE_START_EVENT</td><td>启动准备阶段</td></tr>
 *   <tr><td>STARTING</td><td>是</td><td>START_EVENT</td><td>正在启动，级联启动子组件</td></tr>
 *   <tr><td>STARTED</td><td>是</td><td>AFTER_START_EVENT</td><td>启动完成，正常对外提供服务</td></tr>
 *   <tr><td>STOPPING_PREP</td><td>是</td><td>BEFORE_STOP_EVENT</td><td>停止准备阶段（仍可处理请求）</td></tr>
 *   <tr><td>STOPPING</td><td>否</td><td>STOP_EVENT</td><td>正在停止，级联停止子组件</td></tr>
 *   <tr><td>STOPPED</td><td>否</td><td>AFTER_STOP_EVENT</td><td>停止完成，不再对外服务</td></tr>
 *   <tr><td>DESTROYING</td><td>否</td><td>BEFORE_DESTROY_EVENT</td><td>正在销毁</td></tr>
 *   <tr><td>DESTROYED</td><td>否</td><td>AFTER_DESTROY_EVENT</td><td>销毁完成，对象不可再用</td></tr>
 *   <tr><td>FAILED</td><td>否</td><td>无</td><td>失败状态，需要清理资源</td></tr>
 * </table>
 *
 * @see Lifecycle
 * @see org.apache.catalina.util.LifecycleBase
 */
public enum LifecycleState {

    /**
     * 新建状态：对象刚通过构造函数创建，还未调用 init()。
     * <p>
     * 此时组件的属性可以通过 setter 设置，但业务方法不应被调用。
     * 从 NEW 状态可以触发 init()、start()、stop() 或 destroy()。
     * </p>
     */
    NEW(false, null),

    /**
     * 初始化中状态：init() 已被调用，正在执行 initInternal()。
     * <p>
     * 进入此状态时触发 BEFORE_INIT_EVENT 事件。
     * 此状态是短暂的过渡状态，initInternal() 执行完成后自动转为 INITIALIZED。
     * </p>
     */
    INITIALIZING(false, Lifecycle.BEFORE_INIT_EVENT),

    /**
     * 已初始化状态：init() 执行完毕，组件已准备好但尚未启动。
     * <p>
     * 进入此状态时触发 AFTER_INIT_EVENT 事件。
     * 从 INITIALIZED 可以调用 start() 启动，或 destroy() 直接销毁。
     * </p>
     */
    INITIALIZED(false, Lifecycle.AFTER_INIT_EVENT),

    /**
     * 启动准备状态：start() 已被调用，即将执行 startInternal()。
     * <p>
     * 进入此状态时触发 BEFORE_START_EVENT 事件。
     * 此状态用于在实际启动前做一些准备工作（如配置加载、资源检查）。
     * 组件在此状态下尚不可用（available=false）。
     * </p>
     */
    STARTING_PREP(false, Lifecycle.BEFORE_START_EVENT),

    /**
     * 启动中状态：正在执行 startInternal()，级联启动子组件。
     * <p>
     * 进入此状态时触发 START_EVENT 事件。
     * 此状态下组件标记为可用（available=true），因为子组件的启动可能依赖父组件的服务。
     * 子类必须在 startInternal() 中通过 setState(STARTING) 显式转换到此状态。
     * </p>
     */
    STARTING(true, Lifecycle.START_EVENT),

    /**
     * 已启动状态：start() 执行完毕，组件正常运行，对外提供服务。
     * <p>
     * 进入此状态时触发 AFTER_START_EVENT 事件。
     * 这是组件的正常工作状态，可用（available=true）。
     * 从 STARTED 可以调用 stop() 停止组件。
     * </p>
     */
    STARTED(true, Lifecycle.AFTER_START_EVENT),

    /**
     * 停止准备状态：stop() 已被调用，即将执行 stopInternal()。
     * <p>
     * 进入此状态时触发 BEFORE_STOP_EVENT 事件。
     * 此状态下组件仍标记为可用（available=true），
     * 因为在实际停止前可能还需要处理一些收尾请求或执行清理逻辑。
     * </p>
     */
    STOPPING_PREP(true, Lifecycle.BEFORE_STOP_EVENT),

    /**
     * 停止中状态：正在执行 stopInternal()，级联停止子组件。
     * <p>
     * 进入此状态时触发 STOP_EVENT 事件。
     * 此状态下组件不再可用（available=false），业务方法不应再被调用。
     * 子类必须在 stopInternal() 中通过 setState(STOPPING) 显式转换到此状态。
     * </p>
     */
    STOPPING(false, Lifecycle.STOP_EVENT),

    /**
     * 已停止状态：stop() 执行完毕，组件已停止运行。
     * <p>
     * 进入此状态时触发 AFTER_STOP_EVENT 事件。
     * 组件不再可用。从 STOPPED 状态可以重新 start()，也可以 destroy() 彻底销毁。
     * </p>
     */
    STOPPED(false, Lifecycle.AFTER_STOP_EVENT),

    /**
     * 销毁中状态：destroy() 已被调用，正在执行 destroyInternal()。
     * <p>
     * 进入此状态时触发 BEFORE_DESTROY_EVENT 事件。
     * 此状态是短暂的过渡状态，destroyInternal() 执行完成后自动转为 DESTROYED。
     * </p>
     */
    DESTROYING(false, Lifecycle.BEFORE_DESTROY_EVENT),

    /**
     * 已销毁状态：destroy() 执行完毕，所有资源已释放。
     * <p>
     * 进入此状态时触发 AFTER_DESTROY_EVENT 事件。
     * 组件处于终态，不可再调用任何生命周期方法，对象应被丢弃。
     * </p>
     */
    DESTROYED(false, Lifecycle.AFTER_DESTROY_EVENT),

    /**
     * 失败状态：组件在某个生命周期阶段发生了致命错误。
     * <p>
     * 任何状态都可以转换到 FAILED（发生异常时）。
     * 此状态没有对应的事件（lifecycleEvent 为 null），因为失败是异常路径而非正常流程。
     * 从 FAILED 状态可以调用 stop() 进行清理，或 destroy() 彻底销毁。
     * </p>
     */
    FAILED(false, null);


    /**
     * 组件是否可用——即除了属性 getter/setter 和生命周期方法之外，
     * 组件的业务方法是否可以被安全调用。
     */
    private final boolean available;

    /**
     * 进入该状态时触发的生命周期事件类型。
     * 对应 Lifecycle 接口中定义的 BEFORE_INIT_EVENT、START_EVENT 等常量。
     * 若为 null 表示该状态没有对应的事件。
     */
    private final String lifecycleEvent;


    /**
     * 构造一个生命周期状态枚举值。
     *
     * @param available      组件在此状态下是否可用
     * @param lifecycleEvent 进入此状态时触发的事件名称
     */
    LifecycleState(boolean available, String lifecycleEvent) {
        this.available = available;
        this.lifecycleEvent = lifecycleEvent;
    }


    /**
     * 判断组件在此状态下是否可用。
     * <p>
     * "可用"的定义是：除了属性 getter/setter 和生命周期方法本身之外，
     * 组件的其他公共业务方法是否可以被安全调用。
     * </p>
     * <p>
     * 以下三种状态返回 true：
     * <ul>
     *   <li><strong>STARTING</strong>：正在启动，子组件可能需要调用父组件服务</li>
     *   <li><strong>STARTED</strong>：正常运行，完全可用</li>
     *   <li><strong>STOPPING_PREP</strong>：停止准备阶段，仍可处理收尾请求</li>
     * </ul>
     * </p>
     *
     * @return true 表示组件可用，false 表示不可用
     */
    public boolean isAvailable() {
        return available;
    }


    /**
     * 获取进入该状态时触发的生命周期事件名称。
     * <p>
     * 每个状态（除 NEW 和 FAILED 外）都关联一个事件，
     * 当组件进入该状态时，所有注册的 {@link LifecycleListener} 都会收到通知。
     * </p>
     *
     * @return 事件名称字符串，如果该状态没有对应事件则返回 null
     */
    public String getLifecycleEvent() {
        return lifecycleEvent;
    }
}
