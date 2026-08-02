/**
 * 浏览器会话级登录态工具。
 *
 * <p>Token 只放在 sessionStorage：关闭浏览器即失效，避免把长期凭证保留在本地磁盘。角色和 Workspace 权限不缓存到前端，始终由服务端实时校验。</p>
 */
const ACCESS_TOKEN_KEY = 'insightflow.access-token'

/** 返回当前会话 Bearer Token；没有登录态时返回空字符串。 */
export function getAccessToken() {
  return sessionStorage.getItem(ACCESS_TOKEN_KEY) || ''
}

/** 登录成功后保存短期 Token；密码和 bootstrap 口令永远不写入浏览器存储。 */
export function setAccessToken(token) {
  if (!token) throw new Error('登录响应缺少访问令牌')
  sessionStorage.setItem(ACCESS_TOKEN_KEY, token)
}

/** 主动退出或收到未认证响应时清除唯一会话凭证。 */
export function clearAccessToken() {
  sessionStorage.removeItem(ACCESS_TOKEN_KEY)
}

/** 为同源 API 请求附加 Bearer Token，认证接口本身也可以安全忽略该头。 */
export function installAuthenticatedFetch() {
  const nativeFetch = window.fetch.bind(window)
  window.fetch = async (input, init = {}) => {
    const token = getAccessToken()
    const headers = new Headers(init.headers || {})
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const response = await nativeFetch(input, { ...init, headers })
    if (response.status === 401 && !String(input).includes('/api/v1/auth/')) clearAccessToken()
    return response
  }
}
