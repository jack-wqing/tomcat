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
 * Lifecycle 接口是 Catalina 组件生命周期管理的<strong>核心契约</strong>。
 * 所有需要统一启动/停止/销毁流程的组件（Server、Service、Engine、Host、Context、Wrapper、Connector 等）
 * 都实现此接口，以提供一致的生命周期管理机制。
 *
 * <h3>一、为什么需要 Lifecycle？</h3>
 * <p>
 * Tomcat 是一个高度模块化的 Web 容器，包含数十个相互协作的组件。
 * 如果没有统一的生命周期管理，每个组件各自实现 init/start/stop 方法，会导致：
 * <ul>
 *   <li><strong>启动顺序混乱</strong>：父组件和子组件的启动/停止顺序难以协调</li>
 *   <li><strong>状态不可控</strong>：无法判断组件当前是已启动、已停止还是失败状态</li>
 *   <li><strong>扩展困难</strong>：外部组件难以感知内部组件的状态变化</li>
 *   <li><strong>资源泄漏</strong>：启动失败时已初始化的资源可能无法正确释放</li>
 * </ul>
 * Lifecycle 通过<strong>状态机 + 观察者模式 + 模板方法</strong>的组合设计，优雅地解决了这些问题。
 * </p>
 *
 * <h3>二、设计模式详解</h3>
 *
 * <h4>1. 状态机模式（State Pattern）</h4>
 * <p>
 * 每个 Lifecycle 组件都有明确的状态（由 {@link LifecycleState} 枚举定义），
 * 状态转换必须遵循预定义的规则。非法转换会抛出 {@link LifecycleException}。
 * 这保证了组件始终处于可预测的状态。
 * </p>
 *
 * <h4>2. 观察者模式（Observer Pattern）</h4>
 * <p>
 * 组件状态变化时会触发 {@link LifecycleEvent} 事件，
 * 所有注册的 {@link LifecycleListener} 都会收到通知。
 * 这使得外部组件可以在不修改目标组件代码的情况下，扩展生命周期行为
 * （如 ContextConfig 在 Context 启动时解析 web.xml）。
 * </p>
 *
 * <h4>3. 模板方法模式（Template Method Pattern）</h4>
 * <p>
 * {@link org.apache.catalina.util.LifecycleBase LifecycleBase} 抽象基类
 * 实现了 init/start/stop/destroy 的算法骨架（状态校验→状态转换→委托子类→异常处理），
 * 子类只需实现 initInternal/startInternal/stopInternal/destroyInternal 四个钩子方法。
 * </p>
 *
 * <h3>三、状态转换图（核心）</h3>
 * <pre>
 *            start()
 *  -----------------------------
 *  |                           |
 *  | init()                    |
 * NEW -»-- INITIALIZING        |
 * | |           |              |     ------------------«-----------------------
 * | |           |auto          |     |                                        |
 * | |          \|/    start() \|/   \|/     auto          auto         stop() |
 * | |      INITIALIZED --»-- STARTING_PREP --»- STARTING --»- STARTED --»---  |
 * | |         |                                                            |  |
 * | |destroy()|                                                            |  |
 * | --»-----«--    ------------------------«--------------------------------  ^
 * |     |          |                                                          |
 * |     |         \|/          auto                 auto              start() |
 * |     |     STOPPING_PREP ----»---- STOPPING ------»----- STOPPED -----»-----
 * |    \|/                               ^                     |  ^
 * |     |               stop()           |                     |  |
 * |     |       --------------------------                     |  |
 * |     |       |                                              |  |
 * |     |       |    destroy()                       destroy() |  |
 * |     |    FAILED ----»------ DESTROYING ---«-----------------  |
 * |     |                        ^     |                          |
 * |     |     destroy()          |     |auto                      |
 * |     --------»-----------------    \|/                         |
 * |                                 DESTROYED                     |
 * |                                                               |
 * |                            stop()                             |
 * ----»-----------------------------»------------------------------
 * </pre>
 *
 * <h3>四、关键转换规则</h3>
 * <ul>
 *   <li><strong>任何状态 → FAILED</strong>：发生异常时自动进入失败状态</li>
 *   <li><strong>start() 的幂等性</strong>：在 STARTING_PREP/STARTING/STARTED 状态调用 start() 无效果</li>
 *   <li><strong>NEW 状态自动初始化</strong>：在 NEW 状态调用 start()，会先自动执行 init()</li>
 *   <li><strong>stop() 的幂等性</strong>：在 STOPPING_PREP/STOPPING/STOPPED 状态调用 stop() 无效果</li>
 *   <li><strong>NEW → STOPPED</strong>：父组件启动失败时，可能有子组件仍处于 NEW 状态，
 *       调用 stop() 直接转为 STOPPED，确保所有子组件都能被"停止"</li>
 *   <li><strong>FAILED → STOPPING</strong>：从失败状态停止时，跳过 STOPPING_PREP，
 *       避免短暂标记为"可用"</li>
 *   <li><strong>非法转换抛异常</strong>：其他未定义的转换会抛出 LifecycleException</li>
 * </ul>
 *
 * <h3>五、事件与状态的对应关系</h3>
 * <table border="1">
 *   <tr><th>生命周期方法</th><th>触发的事件顺序</th><th>对应状态转换</th></tr>
 *   <tr>
 *     <td><code>init()</code></td>
 *     <td>BEFORE_INIT → AFTER_INIT</td>
 *     <td>NEW → INITIALIZING → INITIALIZED</td>
 *   </tr>
 *   <tr>
 *     <td><code>start()</code></td>
 *     <td>BEFORE_START → START → AFTER_START</td>
 *     <td>INITIALIZED/STOPPED → STARTING_PREP → STARTING → STARTED</td>
 *   </tr>
 *   <tr>
 *     <td><code>stop()</code></td>
 *     <td>BEFORE_STOP → STOP → AFTER_STOP</td>
 *     <td>STARTED → STOPPING_PREP → STOPPING → STOPPED</td>
 *   </tr>
 *   <tr>
 *     <td><code>destroy()</code></td>
 *     <td>BEFORE_DESTROY → AFTER_DESTROY</td>
 *     <td>STOPPED/FAILED/NEW/INITIALIZED → DESTROYING → DESTROYED</td>
 *   </tr>
 * </table>
 *
 * @author Craig R. McClanahan
 * @see LifecycleState
 * @see LifecycleListener
 * @see LifecycleEvent
 * @see org.apache.catalina.util.LifecycleBase
 */
public interface Lifecycle {


    // ----------------------------------------------------- Manifest Constants
    // 以下常量定义了生命周期事件的类型名称，用于 LifecycleEvent 和 LifecycleListener


    /**
     * "初始化前"事件：组件即将开始初始化时触发。
     * <p>对应状态转换：NEW → INITIALIZING</p>
     */
    String BEFORE_INIT_EVENT = "before_init";


    /**
     * "初始化后"事件：组件初始化完成时触发。
     * <p>对应状态转换：INITIALIZING → INITIALIZED</p>
     */
    String AFTER_INIT_EVENT = "after_init";


    /**
     * "启动中"事件：组件正在启动时触发。
     * <p>
     * 这是级联启动子组件的安全时机——此时父组件已可用，
     * 子组件可以安全地调用父组件的服务。
     * </p>
     * <p>对应状态转换：STARTING_PREP → STARTING</p>
     */
    String START_EVENT = "start";


    /**
     * "启动前"事件：组件即将开始启动时触发。
     * <p>常用于启动前的准备工作，如配置加载、资源检查。</p>
     * <p>对应状态转换：INITIALIZED/STOPPED → STARTING_PREP</p>
     */
    String BEFORE_START_EVENT = "before_start";


    /**
     * "启动后"事件：组件启动完成、完全可用时触发。
     * <p>对应状态转换：STARTING → STARTED</p>
     */
    String AFTER_START_EVENT = "after_start";


    /**
     * "停止中"事件：组件正在停止时触发。
     * <p>
     * 这是级联停止子组件的安全时机——此时父组件仍在运行，
     * 子组件可以安全地完成清理工作。
     * </p>
     * <p>对应状态转换：STOPPING_PREP → STOPPING</p>
     */
    String STOP_EVENT = "stop";


    /**
     * "停止前"事件：组件即将开始停止时触发。
     * <p>
     * 此时组件仍标记为可用（available=true），
     * 可以处理收尾请求或执行停止前的准备逻辑。
     * </p>
     * <p>对应状态转换：STARTED → STOPPING_PREP</p>
     */
    String BEFORE_STOP_EVENT = "before_stop";


    /**
     * "停止后"事件：组件完全停止时触发。
     * <p>对应状态转换：STOPPING → STOPPED</p>
     */
    String AFTER_STOP_EVENT = "after_stop";


    /**
     * "销毁后"事件：组件销毁完成时触发。
     * <p>对应状态转换：DESTROYING → DESTROYED</p>
     */
    String AFTER_DESTROY_EVENT = "after_destroy";


    /**
     * "销毁前"事件：组件即将开始销毁时触发。
     * <p>对应状态转换：STOPPED/FAILED/NEW/INITIALIZED → DESTROYING</p>
     */
    String BEFORE_DESTROY_EVENT = "before_destroy";


    /**
     * "周期性"事件：用于周期性任务（如后台线程定时执行）。
     * <p>
     * 这不是状态转换事件，而是由容器定期触发的自定义事件，
     * 常用于实现周期性检查、过期会话清理等功能。
     * </p>
     */
    String PERIODIC_EVENT = "periodic";


    /**
     * "配置启动"事件：在启动过程中，用于通知配置组件执行配置操作。
     * <p>
     * 触发时机：BEFORE_START_EVENT 之后、START_EVENT 之前。
     * 典型使用场景：HostConfig 部署 Web 应用、ContextConfig 解析 web.xml。
     * </p>
     */
    String CONFIGURE_START_EVENT = "configure_start";


    /**
     * "配置停止"事件：在停止过程中，用于通知配置组件执行反配置操作。
     * <p>
     * 触发时机：STOP_EVENT 之后、AFTER_STOP_EVENT 之前。
     * 典型使用场景：HostConfig 卸载 Web 应用、ContextConfig 清理配置资源。
     * </p>
     */
    String CONFIGURE_STOP_EVENT = "configure_stop";


    // --------------------------------------------------------- Public Methods
    // 生命周期管理的核心方法


    /**
     * 注册一个生命周期监听器。
     * <p>
     * 当组件状态发生变化时，所有已注册的监听器都会收到 {@link LifecycleEvent} 通知。
     * 这是观察者模式的核心方法，允许外部组件在不修改目标组件代码的情况下扩展其生命周期行为。
     * </p>
     *
     * @param listener 要添加的监听器
     */
    void addLifecycleListener(LifecycleListener listener);


    /**
     * 获取当前注册的所有生命周期监听器。
     *
     * @return 监听器数组；如果没有注册任何监听器，返回空数组（不会返回 null）
     */
    LifecycleListener[] findLifecycleListeners();


    /**
     * 移除一个生命周期监听器。
     *
     * @param listener 要移除的监听器
     */
    void removeLifecycleListener(LifecycleListener listener);


    /**
     * 初始化组件。
     * <p>
     * 在对象创建之后、启动之前调用，用于执行一次性的初始化工作
     * （如加载配置、创建资源、建立连接池等）。
     * </p>
     *
     * <h4>状态转换</h4>
     * NEW → INITIALIZING → INITIALIZED
     *
     * <h4>触发的事件顺序</h4>
     * <ol>
     *   <li>{@link #BEFORE_INIT_EVENT}：开始初始化前</li>
     *   <li>{@link #AFTER_INIT_EVENT}：初始化完成后</li>
     * </ol>
     *
     * <h4>调用约束</h4>
     * <ul>
     *   <li>只能从 NEW 状态调用，否则抛出异常</li>
     *   <li>方法是幂等的吗？——否，初始化只能执行一次</li>
     * </ul>
     *
     * @throws LifecycleException 如果初始化过程中发生致命错误
     */
    void init() throws LifecycleException;


    /**
     * 启动组件，使其进入可服务状态。
     * <p>
     * 调用此方法后，组件的公共业务方法（除了属性 getter/setter 和生命周期方法）
     * 就可以被安全调用了。
     * </p>
     *
     * <h4>状态转换</h4>
     * <ul>
     *   <li>NEW → 自动 init() → INITIALIZED → STARTING_PREP → STARTING → STARTED</li>
     *   <li>INITIALIZED → STARTING_PREP → STARTING → STARTED</li>
     *   <li>STOPPED → STARTING_PREP → STARTING → STARTED（重新启动）</li>
     *   <li>STARTING_PREP / STARTING / STARTED → 无操作（幂等）</li>
     * </ul>
     *
     * <h4>触发的事件顺序</h4>
     * <ol>
     *   <li>{@link #BEFORE_START_EVENT}：进入 STARTING_PREP 状态，启动准备</li>
     *   <li>{@link #START_EVENT}：进入 STARTING 状态，此时父组件已可用，可级联启动子组件</li>
     *   <li>{@link #AFTER_START_EVENT}：进入 STARTED 状态，启动完成</li>
     * </ol>
     *
     * <h4>调用约束</h4>
     * <ul>
     *   <li>可从 NEW、INITIALIZED、STOPPED 状态调用</li>
     *   <li>在 STARTING_PREP、STARTING、STARTED 状态调用无效果（幂等）</li>
     *   <li>从 NEW 状态调用时会先自动执行 init()</li>
     * </ul>
     *
     * @throws LifecycleException 如果启动过程中发生致命错误
     */
    void start() throws LifecycleException;


    /**
     * 停止组件，使其退出服务状态。
     * <p>
     * 优雅地终止组件的活动，停止对外提供服务。
     * 停止后的组件可以通过 start() 重新启动，也可以通过 destroy() 彻底销毁。
     * </p>
     *
     * <h4>状态转换</h4>
     * <ul>
     *   <li>STARTED → STOPPING_PREP → STOPPING → STOPPED</li>
     *   <li>FAILED → STOPPING → STOPPED（跳过 STOPPING_PREP，避免短暂"可用"）</li>
     *   <li>NEW → STOPPED（直接转换，用于处理未启动的子组件）</li>
     *   <li>STOPPING_PREP / STOPPING / STOPPED → 无操作（幂等）</li>
     * </ul>
     *
     * <h4>触发的事件顺序</h4>
     * <ol>
     *   <li>{@link #BEFORE_STOP_EVENT}：进入 STOPPING_PREP 状态，停止准备（仍可用）</li>
     *   <li>{@link #STOP_EVENT}：进入 STOPPING 状态，此时父组件仍在运行，可级联停止子组件</li>
     *   <li>{@link #AFTER_STOP_EVENT}：进入 STOPPED 状态，停止完成</li>
     * </ol>
     *
     * <h4>特殊说明</h4>
     * <p>
     * 从 FAILED 状态停止时，虽然也会触发上述三个事件，
     * 但状态会直接从 FAILED 跳到 STOPPING，绕过 STOPPING_PREP。
     * 这是为了避免失败状态的组件被短暂标记为"可用"。
     * </p>
     *
     * @throws LifecycleException 如果停止过程中发生致命错误
     */
    void stop() throws LifecycleException;


    /**
     * 销毁组件，释放所有资源。
     * <p>
     * 销毁后的组件不可再使用，应被丢弃。
     * 与 stop() 不同，destroy() 是终态操作，销毁后无法重新启动。
     * </p>
     *
     * <h4>状态转换</h4>
     * <ul>
     *   <li>STOPPED → DESTROYING → DESTROYED</li>
     *   <li>FAILED → 先 stop() → 再 DESTROYING → DESTROYED</li>
     *   <li>NEW → DESTROYING → DESTROYED（未初始化即销毁）</li>
     *   <li>INITIALIZED → DESTROYING → DESTROYED（已初始化但未启动即销毁）</li>
     *   <li>DESTROYING / DESTROYED → 无操作（幂等）</li>
     * </ul>
     *
     * <h4>触发的事件顺序</h4>
     * <ol>
     *   <li>{@link #BEFORE_DESTROY_EVENT}：进入 DESTROYING 状态，开始销毁</li>
     *   <li>{@link #AFTER_DESTROY_EVENT}：进入 DESTROYED 状态，销毁完成</li>
     * </ol>
     *
     * @throws LifecycleException 如果销毁过程中发生致命错误
     */
    void destroy() throws LifecycleException;


    /**
     * 获取组件当前的生命周期状态。
     *
     * @return 当前状态枚举值
     * @see LifecycleState
     */
    LifecycleState getState();


    /**
     * 获取当前组件状态的字符串表示。
     * <p>
     * 主要用于 JMX 监控和日志输出。
     * <strong>注意：不要通过字符串比较来判断组件状态</strong>，
     * 因为字符串格式可能在不同版本间变化。判断状态应使用 {@link #getState()}。
     * </p>
     *
     * @return 当前状态的名称字符串
     */
    String getStateName();


    /**
     * SingleUse 标记接口：表示组件只能使用一次。
     * <p>
     * 如果组件实现了此接口，调用 stop() 方法后会自动调用 destroy()，
     * 确保组件只被使用一次，停止后即销毁。
     * </p>
     * <p>
     * 适用场景：处理一次性任务的组件（如单次请求处理器、临时部署器等）。
     * </p>
     */
    interface SingleUse {
    }
}
