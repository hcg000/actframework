package act.handler.builtin.controller;

import act.Act;
import act.Destroyable;
import act.app.ActionContext;
import act.app.App;
import act.app.AppInterceptorManager;
import act.controller.meta.ActionMethodMetaInfo;
import act.controller.meta.CatchMethodMetaInfo;
import act.controller.meta.ControllerClassMetaInfo;
import act.controller.meta.InterceptorMethodMetaInfo;
import act.handler.RequestHandlerBase;
import act.view.ActServerError;
import act.view.RenderAny;
import org.osgl._;
import org.osgl.cache.CacheService;
import org.osgl.exception.NotAppliedException;
import org.osgl.http.H;
import org.osgl.logging.L;
import org.osgl.logging.Logger;
import org.osgl.mvc.result.NoResult;
import org.osgl.mvc.result.Result;
import org.osgl.util.C;
import org.osgl.util.E;
import org.osgl.util.S;

import java.util.Collection;
import java.util.ListIterator;

public final class RequestHandlerProxy extends RequestHandlerBase {

    private static Logger logger = L.get(RequestHandlerProxy.class);

    protected enum CacheStrategy {
        NO_CACHE() {
            @Override
            public Result cached(ActionContext actionContext, CacheService cache) {
                return null;
            }
        },
        SESSION_SCOPED() {
            @Override
            protected String cacheKey(ActionContext actionContext) {
                H.Session session = actionContext.session();
                return null == session ? null : super.cacheKey(actionContext, session.id());
            }
        },
        GLOBAL_SCOPED;

        public Result cached(ActionContext actionContext, CacheService cache) {
            return cache.get(cacheKey(actionContext));
        }

        protected String cacheKey(ActionContext actionContext) {
            return cacheKey(actionContext, "");
        }

        protected String cacheKey(ActionContext actionContext, String seed) {
            H.Request request = actionContext.req();
            return S.builder("urlcache:").append(seed).append(request.url()).append(request.query()).append(request.accept()).toString();
        }
    }

    private static final C.List<BeforeInterceptor> globalBeforeInterceptors = C.newList();
    private static final C.List<AfterInterceptor> globalAfterInterceptors = C.newList();
    private static final C.List<FinallyInterceptor> globalFinallyInterceptors = C.newList();
    private static final C.List<ExceptionInterceptor> globalExceptionInterceptors = C.newList();

    static final GroupInterceptorWithResult GLOBAL_BEFORE_INTERCEPTOR = new GroupInterceptorWithResult(globalBeforeInterceptors);
    static final GroupAfterInterceptor GLOBAL_AFTER_INTERCEPTOR = new GroupAfterInterceptor(globalAfterInterceptors);
    static final GroupFinallyInterceptor GLOBAL_FINALLY_INTERCEPTOR = new GroupFinallyInterceptor(globalFinallyInterceptors);
    static final GroupExceptionInterceptor GLOBAL_EXCEPTION_INTERCEPTOR = new GroupExceptionInterceptor(globalExceptionInterceptors);

    private App app;
    private AppInterceptorManager appInterceptor;
    private CacheService cache;
    private CacheStrategy cacheStrategy = CacheStrategy.NO_CACHE;
    private String controllerClassName;
    private String actionMethodName;
    private Boolean requireContextLocal = null;

    private volatile ControllerAction actionHandler = null;
    private C.List<BeforeInterceptor> beforeInterceptors = C.newList();
    private C.List<AfterInterceptor> afterInterceptors = C.newList();
    private C.List<ExceptionInterceptor> exceptionInterceptors = C.newList();
    private C.List<FinallyInterceptor> finallyInterceptors = C.newList();

    final GroupInterceptorWithResult BEFORE_INTERCEPTOR = new GroupInterceptorWithResult(beforeInterceptors);
    final GroupAfterInterceptor AFTER_INTERCEPTOR = new GroupAfterInterceptor(afterInterceptors);
    final GroupFinallyInterceptor FINALLY_INTERCEPTOR = new GroupFinallyInterceptor(finallyInterceptors);
    final GroupExceptionInterceptor EXCEPTION_INTERCEPTOR = new GroupExceptionInterceptor(exceptionInterceptors);

    public RequestHandlerProxy(String actionMethodName, App app) {
        int pos = actionMethodName.lastIndexOf('.');
        final String ERR = "Invalid controller action: %s";
        E.illegalArgumentIf(pos < 0, ERR, actionMethodName);
        controllerClassName = actionMethodName.substring(0, pos);
        E.illegalArgumentIf(S.isEmpty(controllerClassName), ERR, actionMethodName);
        this.actionMethodName = actionMethodName.substring(pos + 1);
        E.illegalArgumentIf(S.isEmpty(this.actionMethodName), ERR, actionMethodName);
        cache = app.config().cacheService("action_proxy");
        this.app = app;
        this.appInterceptor = app.interceptorManager();
    }

    @Override
    protected void releaseResources() {
        _releaseResourceCollections(afterInterceptors);
        _releaseResourceCollections(beforeInterceptors);
        _releaseResourceCollections(exceptionInterceptors);
        _releaseResourceCollections(finallyInterceptors);
        if (null != actionHandler) {
            actionHandler.destroy();
            actionHandler = null;
        }
    }

    public static void releaseGlobalResources() {
        _releaseResourceCollections(globalAfterInterceptors);
        _releaseResourceCollections(globalBeforeInterceptors);
        _releaseResourceCollections(globalExceptionInterceptors);
        _releaseResourceCollections(globalFinallyInterceptors);
    }

    private static void _releaseResourceCollections(Collection<? extends Destroyable> col) {
        Destroyable.Util.destroyAll(col);
    }

    public String controller() {
        return controllerClassName;
    }

    public String action() {
        return actionMethodName;
    }

    @Override
    public void handle(ActionContext context) {
        Result result = cacheStrategy.cached(context, cache);
        try {
            if (null != result) {
                onResult(result, context);
                return;
            }
            ensureAgentsReady();
            ensureContextLocal(context);
            saveActionPath(context);
            result = handleBefore(context);
            if (null == result) {
                result = _handle(context);
            }
            Result afterResult = handleAfter(result, context);
            if (null != afterResult) {
                result = afterResult;
            }
            if (null == result) {
                result = new NoResult();
            }
            onResult(result, context);
        } catch (Exception e) {
            logger.error(e, "Error handling request");
            result = handleException(e, context);
            if (null == result) {
                result = new ActServerError(e, app);
            }
            try {
                onResult(result, context);
            } catch (Exception e2) {
                logger.error(e2, "error rendering exception handle  result");
                onResult(new ActServerError(e2, app), context);
            }
        } finally {
            handleFinally(context);
        }
    }

    protected final void useSessionCache() {
        cacheStrategy = CacheStrategy.SESSION_SCOPED;
    }

    protected final void useGlobalCache() {
        cacheStrategy = CacheStrategy.GLOBAL_SCOPED;
    }

    protected final void registerBeforeInterceptor(BeforeInterceptor interceptor) {
        insertInterceptor(beforeInterceptors, interceptor);
    }

    protected final void registerAfterInterceptor(AfterInterceptor interceptor) {
        insertInterceptor(afterInterceptors, interceptor);
    }

    protected final void registerExceptionInterceptor(ExceptionInterceptor interceptor) {
        insertInterceptor(exceptionInterceptors, interceptor);
    }

    protected final void registerFinallyInterceptor(FinallyInterceptor interceptor) {
        insertInterceptor(finallyInterceptors, interceptor);
    }

    private void onResult(Result result, ActionContext context) {
        context.dissolve();
        try {
            if (result instanceof RenderAny) {
                RenderAny any = (RenderAny) result;
                any.apply(context);
            } else {
                H.Request req = context.req();
                H.Response resp = context.resp();
                result.apply(req, resp);
            }
        } catch (Exception e) {
            context.cacheTemplate(null);
            throw e;
        }
        context.destroy();
    }

    private void ensureAgentsReady() {
        if (null == actionHandler) {
            synchronized (this) {
                if (null == actionHandler) {
                    generateHandlers();
                }
            }
        }
    }

    private void ensureContextLocal(ActionContext context) {
//        if (requireContextLocal) {
//            context.saveLocal();
//        }
    }

    // could be used by View to resolve default path to template
    private void saveActionPath(ActionContext context) {
        StringBuilder sb = S.builder(controllerClassName).append(".").append(actionMethodName);
        String path = sb.toString();
        context.actionPath(path);
    }

    private void generateHandlers() {
        ControllerClassMetaInfo ctrlInfo = app.classLoader().controllerClassMetaInfo(controllerClassName);
        ActionMethodMetaInfo actionInfo = ctrlInfo.action(actionMethodName);
        Act.Mode mode = Act.mode();
        actionHandler = mode.createRequestHandler(actionInfo, app);
        requireContextLocal = false;
        if (actionInfo.appContextInjection().injectVia().isLocal()) {
            requireContextLocal = true;
        }
        App app = this.app;
        for (InterceptorMethodMetaInfo info : ctrlInfo.beforeInterceptors()) {
            beforeInterceptors.add(mode.createBeforeInterceptor(info, app));
            if (info.appContextInjection().injectVia().isLocal()) {
                requireContextLocal = true;
            }
        }
        for (InterceptorMethodMetaInfo info : ctrlInfo.afterInterceptors()) {
            afterInterceptors.add(mode.createAfterInterceptor(info, app));
            if (info.appContextInjection().injectVia().isLocal()) {
                requireContextLocal = true;
            }
        }
        for (CatchMethodMetaInfo info : ctrlInfo.exceptionInterceptors()) {
            exceptionInterceptors.add(mode.createExceptionInterceptor(info, app));
            if (info.appContextInjection().injectVia().isLocal()) {
                requireContextLocal = true;
            }
        }
        for (InterceptorMethodMetaInfo info : ctrlInfo.finallyInterceptors()) {
            finallyInterceptors.add(mode.createFinallyInterceptor(info, app));
            if (info.appContextInjection().injectVia().isLocal()) {
                requireContextLocal = true;
            }
        }
    }

    public void accept(Handler.Visitor visitor) {
        ensureAgentsReady();
        for (BeforeInterceptor i : globalBeforeInterceptors) {
            i.accept(visitor);
        }
        for (BeforeInterceptor i : beforeInterceptors) {
            i.accept(visitor);
        }
        actionHandler.accept(visitor);
        for (AfterInterceptor i : afterInterceptors) {
            i.accept(visitor);
        }
        for (AfterInterceptor i : globalAfterInterceptors) {
            i.accept(visitor);
        }
        for (FinallyInterceptor i : finallyInterceptors) {
            i.accept(visitor);
        }
        for (FinallyInterceptor i : globalFinallyInterceptors) {
            i.accept(visitor);
        }
        for (ExceptionInterceptor i : exceptionInterceptors) {
            i.accept(visitor);
        }
        for (ExceptionInterceptor i : globalExceptionInterceptors) {
            i.accept(visitor);
        }
    }


    private Result handleBefore(ActionContext actionContext) {
        Result r = GLOBAL_BEFORE_INTERCEPTOR.apply(actionContext);
        if (null == r) {
            r = appInterceptor.handleBefore(actionContext);
        }
        if (null == r) {
            r = BEFORE_INTERCEPTOR.apply(actionContext);
        }
        return r;
    }

    private Result _handle(ActionContext actionContext) {
        try {
            return actionHandler.handle(actionContext);
        } catch (Result r) {
            return r;
        }
    }

    private Result handleAfter(Result result, ActionContext actionContext) {
        result = AFTER_INTERCEPTOR.apply(result, actionContext);
        result = appInterceptor.handleAfter(result, actionContext);
        result = GLOBAL_AFTER_INTERCEPTOR.apply(result, actionContext);
        return result;
    }

    private void handleFinally(ActionContext actionContext) {
        FINALLY_INTERCEPTOR.apply(actionContext);
        appInterceptor.handleFinally(actionContext);
        GLOBAL_FINALLY_INTERCEPTOR.apply(actionContext);
    }

    private Result handleException(Exception ex, ActionContext actionContext) {
        Result r = EXCEPTION_INTERCEPTOR.apply(ex, actionContext);
        if (null == r) {
            r = appInterceptor.handleException(ex, actionContext);
        }
        if (null == r) {
            r = GLOBAL_EXCEPTION_INTERCEPTOR.apply(ex, actionContext);
        }
        return r;
    }

    private ActionMethodMetaInfo lookupAction() {
        ControllerClassMetaInfo ctrl = app.classLoader().controllerClassMetaInfo(controllerClassName);
        return ctrl.action(actionMethodName);
    }

    @Override
    public String toString() {
        return S.fmt("%s.%s", controllerClassName, actionMethodName);
    }

    public static void registerGlobalInterceptor(BeforeInterceptor interceptor) {
        insertInterceptor(globalBeforeInterceptors, interceptor);
    }

    public static void registerGlobalInterceptor(AfterInterceptor interceptor) {
        insertInterceptor(globalAfterInterceptors, interceptor);
    }

    public static void registerGlobalInterceptor(FinallyInterceptor interceptor) {
        insertInterceptor(globalFinallyInterceptors, interceptor);
    }

    public static void registerGlobalInterceptor(ExceptionInterceptor interceptor) {
        insertInterceptor(globalExceptionInterceptors, interceptor);
    }

    public static <T extends Handler> void insertInterceptor(C.List<T> list, T i) {
        int sz = list.size();
        if (0 == sz) {
            list.add(i);
        }
        ListIterator<T> itr = list.listIterator();
        while (itr.hasNext()) {
            T t = itr.next();
            int n = i.compareTo(t);
            if (n < 0) {
                itr.add(i);
                return;
            } else if (n == 0) {
                if (i.equals(t)) {
                    // already exists
                    return;
                } else {
                    itr.add(i);
                    return;
                }
            }
        }
        list.add(i);
    }

    public static class GroupInterceptorWithResult extends _.F1<ActionContext, Result> {
        private C.List<? extends ActionHandler> interceptors;

        public GroupInterceptorWithResult(C.List<? extends ActionHandler> interceptors) {
            this.interceptors = interceptors;
        }

        @Override
        public Result apply(ActionContext actionContext) throws NotAppliedException, _.Break {
            try {
                if (interceptors.isEmpty()) return null;
                for (ActionHandler i : interceptors) {
                    Result r = i.handle(actionContext);
                    if (null != r) {
                        return r;
                    }
                }
                return null;
            } catch (Result r) {
                return r;
            }
        }
    }

    public static class GroupAfterInterceptor extends _.F2<Result, ActionContext, Result> {
        private C.List<? extends AfterInterceptor> interceptors;

        public GroupAfterInterceptor(C.List<? extends AfterInterceptor> interceptors) {
            this.interceptors = interceptors;
        }

        @Override
        public Result apply(Result result, ActionContext actionContext) throws NotAppliedException, _.Break {
            for (AfterInterceptor i : interceptors) {
                result = i.handle(result, actionContext);
            }
            return result;
        }
    }

    public static class GroupFinallyInterceptor extends _.F1<ActionContext, Void> {
        private C.List<? extends FinallyInterceptor> interceptors;

        public GroupFinallyInterceptor(C.List<FinallyInterceptor> interceptors) {
            this.interceptors = interceptors;
        }

        @Override
        public Void apply(ActionContext actionContext) throws NotAppliedException, _.Break {
            if (interceptors.isEmpty()) return null;
            for (FinallyInterceptor i : interceptors) {
                i.handle(actionContext);
            }
            return null;
        }
    }

    public static class GroupExceptionInterceptor extends _.F2<Exception, ActionContext, Result> {
        private C.List<? extends ExceptionInterceptor> interceptors;

        public GroupExceptionInterceptor(C.List<? extends ExceptionInterceptor> interceptors) {
            this.interceptors = interceptors;
        }

        @Override
        public Result apply(Exception e, ActionContext actionContext) throws NotAppliedException, _.Break {
            try {
                if (interceptors.isEmpty()) return null;
                for (ExceptionInterceptor i : interceptors) {
                    Result r = i.handle(e, actionContext);
                    if (null != r) {
                        return r;
                    }
                }
                return null;
            } catch (Result r) {
                return r;
            }
        }
    }
}
