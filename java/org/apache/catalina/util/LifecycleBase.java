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
package org.apache.catalina.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;

/**
 * Lifecycle 接口的抽象基类实现，采用 <strong>模板方法模式</strong> 封装状态机转换逻辑。
 * 
 * <h3>设计模式：模板方法模式（Template Method Pattern）</h3>
 * 本类定义了生命周期管理的<strong>算法骨架</strong>，将具体步骤延迟到子类实现：
 * <ul>
 *   <li><strong>基类负责</strong>：状态校验、状态转换、事件触发、异常处理等通用逻辑</li>
 *   <li><strong>子类负责</strong>：通过实现四个抽象方法（initInternal/startInternal/stopInternal/destroyInternal）
 *       完成各自的初始化、启动、停止、销毁的具体业务逻辑</li>
 * </ul>
 * 
 * <h3>核心职责</h3>
 * <ol>
 *   <li><strong>状态机管理</strong>：维护组件状态，确保所有转换符合 Lifecycle 接口定义的规则</li>
 *   <li><strong>线程安全</strong>：所有公共方法使用 synchronized 修饰，配合 volatile state 字段
 *       保证多线程环境下的状态一致性</li>
 *   <li><strong>事件通知</strong>：状态变更时自动触发对应的 LifecycleEvent，通知所有注册的监听器</li>
 *   <li><strong>异常处理</strong>：统一捕获子类异常，自动转换状态为 FAILED，并根据配置决定
 *       是否抛出异常或仅记录日志</li>
 *   <li><strong>幂等性保障</strong>：防止重复启动、重复停止等无效操作</li>
 * </ol>
 * 
 * <h3>子类实现规范</h3>
 * 子类必须实现以下四个抽象方法，且必须遵守约定：
 * <ul>
 *   <li><code>initInternal()</code>：执行初始化，不涉及启动操作（如打开文件句柄、建立连接池等）</li>
 *   <li><code>startInternal()</code>：执行启动，期间必须调用 <code>setState(LifecycleState.STARTING)</code>
 *       触发 START_EVENT 事件</li>
 *   <li><code>stopInternal()</code>：执行停止，期间必须调用 <code>setState(LifecycleState.STOPPING)</code>
 *       触发 STOP_EVENT 事件</li>
 *   <li><code>destroyInternal()</code>：执行销毁，释放所有资源</li>
 * </ul>
 * 
 * @see Lifecycle
 * @see LifecycleState
 */
public abstract class LifecycleBase implements Lifecycle {

    private static final Log log = LogFactory.getLog(LifecycleBase.class);

    private static final StringManager sm = StringManager.getManager(LifecycleBase.class);


    /**
     * 注册的生命周期监听器列表，用于事件通知。
     * 使用 CopyOnWriteArrayList 保证并发安全，支持在迭代过程中添加/删除监听器。
     */
    private final List<LifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();


    /**
     * 当前组件的生命周期状态。
     * 使用 volatile 修饰，确保多线程环境下状态变更的可见性。
     * 初始值为 NEW（刚创建，未初始化）。
     */
    private volatile LifecycleState state = LifecycleState.NEW;


    /**
     * 异常处理策略标志：当子类的生命周期方法抛出异常时，是否重新抛出给调用者。
     * <ul>
     *   <li>true（默认）：异常会被包装为 LifecycleException 重新抛出，上层可感知并处理</li>
     *   <li>false：异常仅记录日志，组件进入 FAILED 状态，但不影响上层调用者</li>
     * </ul>
     */
    private boolean throwOnFailure = true;


    /**
     * Will a {@link LifecycleException} thrown by a sub-class during
     * {@link #initInternal()}, {@link #startInternal()},
     * {@link #stopInternal()} or {@link #destroyInternal()} be re-thrown for
     * the caller to handle or will it be logged instead?
     *
     * @return {@code true} if the exception will be re-thrown, otherwise
     *         {@code false}
     */
    public boolean getThrowOnFailure() {
        return throwOnFailure;
    }


    /**
     * Configure if a {@link LifecycleException} thrown by a sub-class during
     * {@link #initInternal()}, {@link #startInternal()},
     * {@link #stopInternal()} or {@link #destroyInternal()} will be re-thrown
     * for the caller to handle or if it will be logged instead.
     *
     * @param throwOnFailure {@code true} if the exception should be re-thrown,
     *                       otherwise {@code false}
     */
    public void setThrowOnFailure(boolean throwOnFailure) {
        this.throwOnFailure = throwOnFailure;
    }


    @Override
    public void addLifecycleListener(LifecycleListener listener) {
        lifecycleListeners.add(listener);
    }


    @Override
    public LifecycleListener[] findLifecycleListeners() {
        return lifecycleListeners.toArray(new LifecycleListener[0]);
    }


    @Override
    public void removeLifecycleListener(LifecycleListener listener) {
        lifecycleListeners.remove(listener);
    }


    /**
     * 触发生命周期事件，通知所有注册的监听器。
     * 这是观察者模式的核心实现，允许外部组件在组件状态变化时执行回调逻辑。
     *
     * @param type 事件类型，如 BEFORE_INIT_EVENT、START_EVENT 等，定义在 Lifecycle 接口中
     * @param data 事件附带的数据，可为 null
     */
    protected void fireLifecycleEvent(String type, Object data) {
        LifecycleEvent event = new LifecycleEvent(this, type, data);
        for (LifecycleListener listener : lifecycleListeners) {
            listener.lifecycleEvent(event);
        }
    }


    /**
     * <strong>模板方法</strong>：初始化组件，将状态从 NEW 转换为 INITIALIZED。
     * 
     * <h4>执行流程</h4>
     * <ol>
     *   <li><strong>状态校验</strong>：仅允许从 NEW 状态调用，否则抛出 LifecycleException</li>
     *   <li><strong>状态转换</strong>：NEW → INITIALIZING（触发 BEFORE_INIT_EVENT）</li>
     *   <li><strong>委托子类</strong>：调用抽象方法 {@link #initInternal()}，由子类实现具体初始化逻辑</li>
     *   <li><strong>状态转换</strong>：INITIALIZING → INITIALIZED（触发 AFTER_INIT_EVENT）</li>
     *   <li><strong>异常处理</strong>：若 initInternal() 抛出异常，调用 handleSubClassException()
     *       将状态转为 FAILED，并根据配置决定是否重新抛出异常</li>
     * </ol>
     * 
     * <h4>幂等性</h4>
     * 非 NEW 状态调用会抛出异常，确保初始化操作只能执行一次。
     *
     * @throws LifecycleException 如果状态不合法或初始化失败
     */
    @Override
    public final synchronized void init() throws LifecycleException {
        if (!state.equals(LifecycleState.NEW)) {
            invalidTransition(BEFORE_INIT_EVENT);
        }

        try {
            setStateInternal(LifecycleState.INITIALIZING, null, false);
            initInternal();
            setStateInternal(LifecycleState.INITIALIZED, null, false);
        } catch (Throwable t) {
            handleSubClassException(t, "lifecycleBase.initFail", toString());
        }
    }


    /**
     * <strong>抽象钩子方法</strong>：子类必须实现此方法，执行组件特有的初始化逻辑。
     * 
     * <h4>子类实现要求</h4>
     * <ul>
     *   <li>执行一次性初始化操作，如创建资源、初始化配置等</li>
     *   <li>不应启动服务或打开网络监听（这些应在 startInternal() 中执行）</li>
     *   <li>若初始化失败，应抛出 LifecycleException</li>
     * </ul>
     * 
     * <h4>典型用途</h4>
     * <ul>
     *   <li>创建线程池、连接池</li>
     *   <li>初始化配置参数</li>
     *   <li>加载静态资源</li>
     * </ul>
     *
     * @throws LifecycleException 如果初始化失败
     */
    protected abstract void initInternal() throws LifecycleException;


    /**
     * <strong>模板方法</strong>：启动组件，将状态转换为 STARTED。
     * 
     * <h4>执行流程</h4>
     * <ol>
     *   <li><strong>幂等性检查</strong>：若已处于 STARTING_PREP、STARTING、STARTED 状态，直接返回，不重复启动</li>
     *   <li><strong>前置状态处理</strong>：
     *     <ul>
     *       <li>NEW 状态：自动调用 {@link #init()} 完成初始化</li>
     *       <li>FAILED 状态：先调用 {@link #stop()} 清理，再重新启动</li>
     *       <li>其他非法状态：抛出 LifecycleException</li>
     *     </ul>
     *   </li>
     *   <li><strong>状态转换</strong>：INITIALIZED/STOPPED → STARTING_PREP（触发 BEFORE_START_EVENT）</li>
     *   <li><strong>委托子类</strong>：调用抽象方法 {@link #startInternal()}，由子类实现具体启动逻辑</li>
     *   <li><strong>状态验证与转换</strong>：
     *     <ul>
     *       <li>若子类将状态设为 FAILED（受控失败）：调用 stop() 完成清理</li>
     *       <li>若状态不是 STARTING：抛出异常（子类未正确调用 setState(STARTING)）</li>
     *       <li>正常情况：STARTING → STARTED（触发 AFTER_START_EVENT）</li>
     *     </ul>
     *   </li>
     *   <li><strong>异常处理</strong>：若 startInternal() 抛出异常，标记为 FAILED 并根据配置决定是否抛出</li>
     * </ol>
     * 
     * <h4>失败处理策略</h4>
     * <ul>
     *   <li><strong>受控失败</strong>：子类主动调用 setState(FAILED)，此时 stop() 会被调用清理资源，父组件可继续启动</li>
     *   <li><strong>非受控失败</strong>：子类抛出异常，由 handleSubClassException() 处理，异常会传递给父组件</li>
     * </ul>
     *
     * @throws LifecycleException 如果状态不合法或启动失败
     */
    @Override
    public final synchronized void start() throws LifecycleException {

        if (LifecycleState.STARTING_PREP.equals(state) || LifecycleState.STARTING.equals(state) ||
                LifecycleState.STARTED.equals(state)) {

            if (log.isDebugEnabled()) {
                Exception e = new LifecycleException();
                log.debug(sm.getString("lifecycleBase.alreadyStarted", toString()), e);
            } else if (log.isInfoEnabled()) {
                log.info(sm.getString("lifecycleBase.alreadyStarted", toString()));
            }

            return;
        }

        if (state.equals(LifecycleState.NEW)) {
            init();
        } else if (state.equals(LifecycleState.FAILED)) {
            stop();
        } else if (!state.equals(LifecycleState.INITIALIZED) &&
                !state.equals(LifecycleState.STOPPED)) {
            invalidTransition(BEFORE_START_EVENT);
        }

        try {
            setStateInternal(LifecycleState.STARTING_PREP, null, false);
            startInternal();
            if (state.equals(LifecycleState.FAILED)) {
                // 受控失败：组件主动将自己置于 FAILED 状态，调用 stop() 完成清理
                stop();
            } else if (!state.equals(LifecycleState.STARTING)) {
                // 校验：子类必须在 startInternal() 中调用 setState(STARTING)
                invalidTransition(AFTER_START_EVENT);
            } else {
                setStateInternal(LifecycleState.STARTED, null, false);
            }
        } catch (Throwable t) {
            // 非受控失败：将组件置于 FAILED 状态并抛出异常
            handleSubClassException(t, "lifecycleBase.startFail", toString());
        }
    }


    /**
     * <strong>抽象钩子方法</strong>：子类必须实现此方法，执行组件特有的启动逻辑。
     * 
     * <h4>子类实现要求（非常重要）</h4>
     * <ul>
     *   <li><strong>必须调用 setState(STARTING)</strong>：这是触发 START_EVENT 事件的唯一方式，
     *       父组件依赖此事件来协调子组件的启动顺序</li>
     *   <li>执行启动操作，如打开网络监听、启动线程池等</li>
     *   <li>若启动失败，有两种选择：
     *     <ul>
     *       <li>抛出 LifecycleException：会导致父组件也启动失败（非受控失败）</li>
     *       <li>调用 setState(FAILED)：仅自身失败，父组件继续启动其他子组件（受控失败）</li>
     *     </ul>
     *   </li>
     * </ul>
     * 
     * <h4>典型用途</h4>
     * <ul>
     *   <li>启动 Socket 监听（如 Http11NioProtocol）</li>
     *   <li>启动后台线程（如定时任务）</li>
     *   <li>级联启动子组件（如 Engine 启动所有 Host）</li>
     * </ul>
     *
     * @throws LifecycleException 如果启动失败（非受控失败模式）
     */
    protected abstract void startInternal() throws LifecycleException;


    /**
     * <strong>模板方法</strong>：停止组件，将状态转换为 STOPPED。
     * 
     * <h4>执行流程</h4>
     * <ol>
     *   <li><strong>幂等性检查</strong>：若已处于 STOPPING_PREP、STOPPING、STOPPED 状态，直接返回，不重复停止</li>
     *   <li><strong>NEW 状态特殊处理</strong>：直接转为 STOPPED，用于处理父组件启动失败时，
     *       子组件可能仍处于 NEW 状态的场景</li>
     *   <li><strong>状态校验</strong>：仅允许从 STARTED 或 FAILED 状态调用，否则抛出 LifecycleException</li>
     *   <li><strong>状态转换</strong>：
     *     <ul>
     *       <li>STARTED → STOPPING_PREP（触发 BEFORE_STOP_EVENT）</li>
     *       <li>FAILED → 直接触发 BEFORE_STOP_EVENT（不经过 STOPPING_PREP，避免短暂标记为可用）</li>
     *     </ul>
     *   </li>
     *   <li><strong>委托子类</strong>：调用抽象方法 {@link #stopInternal()}，由子类实现具体停止逻辑</li>
     *   <li><strong>状态验证与转换</strong>：
     *     <ul>
     *       <li>若状态不是 STOPPING 或 FAILED：抛出异常（子类未正确调用 setState(STOPPING)）</li>
     *       <li>正常情况：STOPPING → STOPPED（触发 AFTER_STOP_EVENT）</li>
     *     </ul>
     *   </li>
     *   <li><strong>异常处理</strong>：若 stopInternal() 抛出异常，标记为 FAILED 并根据配置决定是否抛出</li>
     *   <li><strong>SingleUse 清理</strong>：若组件实现了 {@link Lifecycle.SingleUse} 接口，
     *       停止完成后自动调用 destroy() 销毁</li>
     * </ol>
     *
     * @throws LifecycleException 如果状态不合法或停止失败
     */
    @Override
    public final synchronized void stop() throws LifecycleException {

        if (LifecycleState.STOPPING_PREP.equals(state) || LifecycleState.STOPPING.equals(state) ||
                LifecycleState.STOPPED.equals(state)) {

            if (log.isDebugEnabled()) {
                Exception e = new LifecycleException();
                log.debug(sm.getString("lifecycleBase.alreadyStopped", toString()), e);
            } else if (log.isInfoEnabled()) {
                log.info(sm.getString("lifecycleBase.alreadyStopped", toString()));
            }

            return;
        }

        if (state.equals(LifecycleState.NEW)) {
            state = LifecycleState.STOPPED;
            return;
        }

        if (!state.equals(LifecycleState.STARTED) && !state.equals(LifecycleState.FAILED)) {
            invalidTransition(BEFORE_STOP_EVENT);
        }

        try {
            if (state.equals(LifecycleState.FAILED)) {
                // FAILED 状态不经过 STOPPING_PREP（避免短暂标记为可用），直接触发 BEFORE_STOP_EVENT
                fireLifecycleEvent(BEFORE_STOP_EVENT, null);
            } else {
                setStateInternal(LifecycleState.STOPPING_PREP, null, false);
            }

            stopInternal();

            // 校验：子类必须在 stopInternal() 中调用 setState(STOPPING)
            if (!state.equals(LifecycleState.STOPPING) && !state.equals(LifecycleState.FAILED)) {
                invalidTransition(AFTER_STOP_EVENT);
            }

            setStateInternal(LifecycleState.STOPPED, null, false);
        } catch (Throwable t) {
            handleSubClassException(t, "lifecycleBase.stopFail", toString());
        } finally {
            if (this instanceof Lifecycle.SingleUse) {
                // SingleUse 组件停止后自动销毁
                setStateInternal(LifecycleState.STOPPED, null, false);
                destroy();
            }
        }
    }


    /**
     * <strong>抽象钩子方法</strong>：子类必须实现此方法，执行组件特有的停止逻辑。
     * 
     * <h4>子类实现要求（非常重要）</h4>
     * <ul>
     *   <li><strong>必须调用 setState(STOPPING)</strong>：这是触发 STOP_EVENT 事件的唯一方式，
     *       父组件依赖此事件来协调子组件的停止顺序</li>
     *   <li>执行停止操作，如关闭网络监听、停止线程池等</li>
     *   <li>若停止失败，可抛出 LifecycleException</li>
     * </ul>
     * 
     * <h4>典型用途</h4>
     * <ul>
     *   <li>关闭 Socket 监听（如 Http11NioProtocol）</li>
     *   <li>停止后台线程</li>
     *   <li>级联停止子组件（如 Engine 停止所有 Host）</li>
     * </ul>
     *
     * @throws LifecycleException 如果停止失败
     */
    protected abstract void stopInternal() throws LifecycleException;


    /**
     * <strong>模板方法</strong>：销毁组件，将状态转换为 DESTROYED。
     * 
     * <h4>执行流程</h4>
     * <ol>
     *   <li><strong>FAILED 状态处理</strong>：若处于 FAILED 状态，先调用 stop() 完成清理，
     *       即使 stop() 失败也继续销毁</li>
     *   <li><strong>幂等性检查</strong>：若已处于 DESTROYING 或 DESTROYED 状态，直接返回，不重复销毁</li>
     *   <li><strong>状态校验</strong>：仅允许从 STOPPED、FAILED、NEW、INITIALIZED 状态调用，
     *       否则抛出 LifecycleException</li>
     *   <li><strong>状态转换</strong>：进入 DESTROYING（触发 BEFORE_DESTROY_EVENT）</li>
     *   <li><strong>委托子类</strong>：调用抽象方法 {@link #destroyInternal()}，由子类实现具体销毁逻辑</li>
     *   <li><strong>状态转换</strong>：DESTROYING → DESTROYED（触发 AFTER_DESTROY_EVENT）</li>
     *   <li><strong>异常处理</strong>：若 destroyInternal() 抛出异常，标记为 FAILED 并根据配置决定是否抛出</li>
     * </ol>
     * 
     * <h4>允许销毁的状态</h4>
     * <ul>
     *   <li>STOPPED：正常停止后销毁</li>
     *   <li>FAILED：失败后强制销毁</li>
     *   <li>NEW：未初始化即销毁</li>
     *   <li>INITIALIZED：已初始化但未启动即销毁</li>
     * </ul>
     *
     * @throws LifecycleException 如果状态不合法或销毁失败
     */
    @Override
    public final synchronized void destroy() throws LifecycleException {
        if (LifecycleState.FAILED.equals(state)) {
            try {
                // FAILED 状态先调用 stop() 完成清理
                stop();
            } catch (LifecycleException e) {
                // stop() 失败仅记录日志，仍继续销毁
                log.error(sm.getString("lifecycleBase.destroyStopFail", toString()), e);
            }
        }

        if (LifecycleState.DESTROYING.equals(state) || LifecycleState.DESTROYED.equals(state)) {
            if (log.isDebugEnabled()) {
                Exception e = new LifecycleException();
                log.debug(sm.getString("lifecycleBase.alreadyDestroyed", toString()), e);
            } else if (log.isInfoEnabled() && !(this instanceof Lifecycle.SingleUse)) {
                // SingleUse 组件会被自动调用多次 destroy()，因此不记录 info 级别日志
                log.info(sm.getString("lifecycleBase.alreadyDestroyed", toString()));
            }

            return;
        }

        if (!state.equals(LifecycleState.STOPPED) && !state.equals(LifecycleState.FAILED) &&
                !state.equals(LifecycleState.NEW) && !state.equals(LifecycleState.INITIALIZED)) {
            invalidTransition(BEFORE_DESTROY_EVENT);
        }

        try {
            setStateInternal(LifecycleState.DESTROYING, null, false);
            destroyInternal();
            setStateInternal(LifecycleState.DESTROYED, null, false);
        } catch (Throwable t) {
            handleSubClassException(t, "lifecycleBase.destroyFail", toString());
        }
    }


    /**
     * <strong>抽象钩子方法</strong>：子类必须实现此方法，执行组件特有的销毁逻辑。
     * 
     * <h4>子类实现要求</h4>
     * <ul>
     *   <li>释放所有资源，如关闭连接、释放内存等</li>
     *   <li>不应抛出异常（若必须抛出，应使用 LifecycleException）</li>
     *   <li>此方法被调用时，组件可能处于 STOPPED、FAILED、NEW 或 INITIALIZED 状态，
     *       因此需要处理各种状态下的资源释放</li>
     * </ul>
     * 
     * <h4>典型用途</h4>
     * <ul>
     *   <li>关闭数据库连接池</li>
     *   <li>释放内存缓存</li>
     *   <li>级联销毁子组件</li>
     * </ul>
     *
     * @throws LifecycleException 如果销毁失败
     */
    protected abstract void destroyInternal() throws LifecycleException;


    @Override
    public LifecycleState getState() {
        return state;
    }


    @Override
    public String getStateName() {
        return getState().toString();
    }


    /**
     * 更新组件状态，并触发对应的生命周期事件。
     * <p>
     * 子类在 startInternal() 和 stopInternal() 中必须调用此方法来触发 START_EVENT 和 STOP_EVENT。
     *
     * @param state 新的组件状态，不能为 null
     * @throws LifecycleException 如果状态转换不合法
     */
    protected synchronized void setState(LifecycleState state) throws LifecycleException {
        setStateInternal(state, null, true);
    }


    /**
     * 更新组件状态，并触发对应的生命周期事件（带附加数据）。
     *
     * @param state 新的组件状态，不能为 null
     * @param data  传递给生命周期事件的数据，可为 null
     * @throws LifecycleException 如果状态转换不合法
     */
    protected synchronized void setState(LifecycleState state, Object data)
            throws LifecycleException {
        setStateInternal(state, data, true);
    }


    /**
     * 内部状态设置方法，执行实际的状态转换和事件触发。
     * 
     * <h4>状态转换校验规则（check=true 时）</h4>
     * 子类仅允许以下状态转换：
     * <ul>
     *   <li><strong>任何状态 → FAILED</strong>：发生异常时的通用转换</li>
     *   <li><strong>STARTING_PREP → STARTING</strong>：由 startInternal() 触发</li>
     *   <li><strong>STOPPING_PREP → STOPPING</strong>：由 stopInternal() 触发</li>
     *   <li><strong>FAILED → STOPPING</strong>：由 stopInternal() 在失败状态下触发</li>
     * </ul>
     * 
     * <h4>事件触发机制</h4>
     * 每个 LifecycleState 枚举值关联一个事件类型（通过 getLifecycleEvent() 获取），
     * 状态变更时自动触发对应的事件通知。
     *
     * @param state 新的组件状态
     * @param data  传递给生命周期事件的数据
     * @param check 是否校验状态转换合法性（模板方法内部调用时传 false，子类调用时传 true）
     * @throws LifecycleException 如果状态转换不合法（仅在 check=true 时）
     */
    private synchronized void setStateInternal(LifecycleState state, Object data, boolean check)
            throws LifecycleException {

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("lifecycleBase.setState", this, state));
        }

        if (check) {
            // 校验：null 不是合法状态
            if (state == null) {
                invalidTransition("null");
                return;
            }

            // 校验：子类仅允许以下四种转换
            // 1. 任何状态 → FAILED（异常情况）
            // 2. STARTING_PREP → STARTING（startInternal() 中调用）
            // 3. STOPPING_PREP → STOPPING（stopInternal() 中调用）
            // 4. FAILED → STOPPING（stopInternal() 在失败状态下调用）
            if (!(state == LifecycleState.FAILED ||
                    (this.state == LifecycleState.STARTING_PREP &&
                            state == LifecycleState.STARTING) ||
                    (this.state == LifecycleState.STOPPING_PREP &&
                            state == LifecycleState.STOPPING) ||
                    (this.state == LifecycleState.FAILED &&
                            state == LifecycleState.STOPPING))) {
                invalidTransition(state.name());
            }
        }

        // 更新状态并触发事件
        this.state = state;
        String lifecycleEvent = state.getLifecycleEvent();
        if (lifecycleEvent != null) {
            fireLifecycleEvent(lifecycleEvent, data);
        }
    }


    /**
     * 处理非法状态转换，抛出详细的异常信息。
     *
     * @param type 尝试转换的事件类型或状态名称
     * @throws LifecycleException 包含当前状态和尝试的转换类型的详细错误信息
     */
    private void invalidTransition(String type) throws LifecycleException {
        String msg = sm.getString("lifecycleBase.invalidTransition", type, toString(), state);
        throw new LifecycleException(msg);
    }


    /**
     * 统一处理子类生命周期方法抛出的异常。
     * 
     * <h4>处理流程</h4>
     * <ol>
     *   <li><strong>标记失败</strong>：将组件状态设为 FAILED</li>
     *   <li><strong>异常分类</strong>：使用 ExceptionUtils.handleThrowable() 判断是否为严重错误（如 ThreadDeath、VirtualMachineError）</li>
     *   <li><strong>异常处理策略</strong>：
     *     <ul>
     *       <li>throwOnFailure=true（默认）：将异常包装为 LifecycleException 重新抛出，上层可感知并处理</li>
     *       <li>throwOnFailure=false：仅记录日志，组件进入 FAILED 状态，但不影响上层调用者</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param t    子类抛出的异常
     * @param key  错误消息的资源键
     * @param args 错误消息的参数
     * @throws LifecycleException 如果 throwOnFailure 为 true
     */
    private void handleSubClassException(Throwable t, String key, Object... args) throws LifecycleException {
        setStateInternal(LifecycleState.FAILED, null, false);
        ExceptionUtils.handleThrowable(t);
        String msg = sm.getString(key, args);
        if (getThrowOnFailure()) {
            // 将异常包装为 LifecycleException 抛出
            if (!(t instanceof LifecycleException)) {
                t = new LifecycleException(msg, t);
            }
            throw (LifecycleException) t;
        } else {
            // 仅记录日志，不抛出异常
            log.error(msg, t);
        }
    }
}
