/**
 * Intelligent Resume Screening System - In-Memory Authentication Module
 * File: frontend/js/auth.js
 *
 * Strict Security Architecture Rules:
 * 1. The 15-minute JWT access token is stored ONLY in JavaScript memory.
 * 2. NEVER store access tokens or passwords in localStorage, sessionStorage, or JavaScript cookies.
 * 3. The refresh token is delivered as an HttpOnly cookie and must NEVER be read or parsed by client JS.
 * 4. All state-changing auth calls include credentials: 'include'.
 * 5. CSRF-protected endpoints (refresh/logout) include 'X-Requested-With: XMLHttpRequest'.
 * 6. Single-flight within tabs and Web Locks API / cross-tab mutex across tabs prevent duplicate refresh collisions.
 * 7. Tabs recheck session state after acquiring the lock to prevent simultaneous rotation.
 * 8. Never broadcast raw JWTs or refresh tokens between tabs.
 * 9. Non-sensitive cross-tab events (REFRESH_COMPLETED, LOGOUT_COMPLETED) use BroadcastChannel safely.
 * 10. Centralized requireAuth() and redirectIfAuthenticated() guards protect frontend routes.
 * 11. Local signed-out state prevents failed/offline logouts from inadvertently auto-restoring sessions.
 */

(function (window) {
  'use strict';

  // In-Memory Authentication State (Never written to persistent browser storage)
  let _accessToken = null;
  let _currentUser = null;
  let _tokenExpiresAt = 0; // Expiration timestamp in milliseconds
  let _lastRefreshedAt = 0; // Timestamp of last successful refresh completion in this tab

  // Single-flight refresh promise within the current tab
  let _activeRefreshPromise = null;

  // Key for local signed-out tracking (stores strictly non-sensitive boolean and timestamp)
  const SIGNED_OUT_STORAGE_KEY = 'auth_signed_out_state';

  // Safe non-sensitive cross-tab coordination channel
  // NEVER broadcasts JWTs, refresh tokens, or user credentials
  const _authChannel = (typeof window !== 'undefined' && typeof window.BroadcastChannel === 'function')
    ? new window.BroadcastChannel('auth_sync_channel')
    : null;

  /**
   * Reads the local signed-out tracking state.
   * @returns {{ signedOut: boolean, serverRevoked: boolean, timestamp: number }|null}
   */
  function getSignedOutState() {
    if (typeof window === 'undefined' || !window.localStorage || typeof window.localStorage.getItem !== 'function') {
      return null;
    }
    try {
      const raw = window.localStorage.getItem(SIGNED_OUT_STORAGE_KEY);
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      return (parsed && parsed.signedOut) ? parsed : null;
    } catch (e) {
      return null;
    }
  }

  /**
   * Checks whether the user has explicitly signed out locally.
   * Prevents an orphaned HttpOnly refresh cookie from auto-restoring after failed/offline logout.
   * @returns {boolean}
   */
  function isLocallySignedOut() {
    return getSignedOutState() !== null;
  }

  /**
   * Sets the local signed-out tracking state without storing any tokens or credentials.
   * @param {boolean} serverRevoked - True if the server confirmed revocation; false if unreachable
   */
  function setSignedOutState(serverRevoked) {
    if (typeof window === 'undefined' || !window.localStorage || typeof window.localStorage.setItem !== 'function') {
      return;
    }
    try {
      window.localStorage.setItem(SIGNED_OUT_STORAGE_KEY, JSON.stringify({
        signedOut: true,
        serverRevoked: Boolean(serverRevoked),
        timestamp: Date.now()
      }));
    } catch (e) {}
  }

  /**
   * Clears the local signed-out tracking state upon deliberate user login.
   */
  function clearSignedOutState() {
    if (typeof window === 'undefined' || !window.localStorage || typeof window.localStorage.removeItem !== 'function') {
      return;
    }
    try {
      window.localStorage.removeItem(SIGNED_OUT_STORAGE_KEY);
    } catch (e) {}
  }

  /**
   * Internal handler for cross-tab logout synchronization.
   * Clears memory state and redirects protected pages to login.html.
   * @param {object} [data]
   */
  function handleCrossTabLogout(data) {
    clearAuth();
    const serverRevoked = (data && typeof data.serverRevoked === 'boolean') ? data.serverRevoked : false;
    setSignedOutState(serverRevoked);

    if (typeof window !== 'undefined' && window.location && window.location.pathname && !window.location.pathname.endsWith('login.html')) {
      const targetParam = serverRevoked ? 'logout_status=success' : 'logout_status=offline';
      window.location.href = `login.html?${targetParam}`;
    }
  }

  // 1. Listen for BroadcastChannel logout events
  if (_authChannel) {
    _authChannel.onmessage = function (event) {
      const data = event.data;
      if (!data || typeof data !== 'object') return;

      if (data.type === 'REFRESH_COMPLETED') {
        // Notification that another tab completed a refresh.
        // NOTE: A waiting tab DOES NOT assume it has received another tab's access token,
        // as JWTs are never broadcast. If this tab has a valid in-memory token, it continues using it;
        // if its token is expired or absent, it will safely obtain its own through the shared HttpOnly cookie.
      } else if (data.type === 'LOGOUT_COMPLETED') {
        handleCrossTabLogout(data);
      }
    };
  }

  // 2. Safe fallback listener using window storage event for environments without BroadcastChannel
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    window.addEventListener('storage', function (event) {
      if (event && event.key === 'auth_logout_sync' && event.newValue) {
        try {
          const syncData = JSON.parse(event.newValue);
          handleCrossTabLogout(syncData);
        } catch (e) {
          handleCrossTabLogout({ serverRevoked: false });
        }
      }
    });
  }

  /**
   * Helper to retrieve backend API base URL from api.js or fallback.
   * @returns {string} API Base URL
   */
  function getApiBaseUrl() {
    if (typeof window.getBaseUrl === 'function') {
      return window.getBaseUrl();
    }
    if (window.api && typeof window.api.getBaseUrl === 'function') {
      return window.api.getBaseUrl();
    }
    return 'http://localhost:8080';
  }

  /**
   * Returns current access token from memory.
   * @returns {string|null}
   */
  function getAccessToken() {
    return _accessToken;
  }

  /**
   * Sets current access token in memory with specified or default 15-minute validity window.
   * @param {string|null} token
   * @param {number} [expiresIn=900]
   */
  function setAccessToken(token, expiresIn = 900) {
    _accessToken = token || null;
    if (_accessToken && Number(expiresIn) > 0) {
      _tokenExpiresAt = Date.now() + (Number(expiresIn) * 1000);
    } else {
      _tokenExpiresAt = 0;
    }
  }

  /**
   * Returns current authenticated user profile from memory.
   * @returns {object|null}
   */
  function getCurrentUser() {
    return _currentUser ? { ..._currentUser } : null;
  }

  /**
   * Sets current user profile in memory.
   * @param {object|null} user
   */
  function setCurrentUser(user) {
    _currentUser = user ? { ...user } : null;
  }

  /**
   * Sets both access token, expiration, and user profile atomically from auth response.
   * @param {object} authResponse
   */
  function setAuthData(authResponse) {
    if (!authResponse || !authResponse.accessToken) {
      clearAuth();
      return;
    }
    _accessToken = authResponse.accessToken;
    _currentUser = authResponse.user || null;
    const expiresIn = typeof authResponse.expiresIn === 'number' ? authResponse.expiresIn : 900;
    _tokenExpiresAt = Date.now() + (expiresIn * 1000);
    _lastRefreshedAt = Date.now();
  }

  /**
   * Checks whether the user is currently authenticated in memory.
   * @returns {boolean}
   */
  function isAuthenticated() {
    return Boolean(_accessToken && _currentUser);
  }

  /**
   * Checks whether the current access token is present and still within its validity window.
   * Includes a 10-second safety buffer to prevent edge-of-expiry request rejections.
   * @returns {boolean}
   */
  function isTokenValid() {
    return Boolean(_accessToken && Date.now() < (_tokenExpiresAt - 10000));
  }

  /**
   * Clears all in-memory authentication state.
   */
  function clearAuth() {
    _accessToken = null;
    _currentUser = null;
    _tokenExpiresAt = 0;
    _lastRefreshedAt = 0;
    _activeRefreshPromise = null;
  }

  /**
   * Internal function to execute the POST /api/v1/auth/refresh network call.
   * Automatically handles HttpOnly cookie delivery, CSRF verification, and status mapping.
   *
   * @returns {Promise<string>} The new access token
   */
  async function _executeRefreshNetworkCall() {
    const baseUrl = getApiBaseUrl();
    const url = `${baseUrl}/api/v1/auth/refresh`;

    let response;
    try {
      response = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
          'X-Requested-With': 'XMLHttpRequest' // Required by backend CSRF protection
        },
        credentials: 'include' // Delivers the HttpOnly refresh-token cookie
      });
    } catch (networkErr) {
      const err = new Error(
        `Network connection failed during session refresh: Unable to reach backend server at ${baseUrl}.`
      );
      err.isNetworkError = true;
      err.originalError = networkErr;
      throw err;
    }

    let responseData = null;
    try {
      const contentType = response.headers.get('content-type') || '';
      if (contentType.includes('application/json')) {
        responseData = await response.json();
      }
    } catch (e) {
      // Non-JSON response
    }

    if (!response.ok) {
      let errorMessage = 'Failed to refresh authentication session.';
      if (response.status === 401 || response.status === 403) {
        errorMessage = 'Authentication session expired or invalid. Please sign in again.';
      } else if (responseData && responseData.message) {
        errorMessage = responseData.message;
      }
      const err = new Error(errorMessage);
      err.status = response.status;
      err.data = responseData;
      err.isAuthError = (response.status === 401 || response.status === 403);
      throw err;
    }

    if (!responseData || !responseData.accessToken) {
      const err = new Error('Invalid refresh response from server: Missing access token.');
      err.status = 500;
      throw err;
    }

    // Update in-memory state strictly (Never written to persistent storage)
    setAuthData(responseData);

    // Notify other tabs via safe BroadcastChannel that a refresh completed
    // (Never broadcasts the JWT or refresh token)
    if (_authChannel) {
      try {
        _authChannel.postMessage({ type: 'REFRESH_COMPLETED', timestamp: Date.now() });
      } catch (broadcastErr) {
        // Ignore broadcast failure
      }
    }

    return responseData.accessToken;
  }

  /**
   * Acquires a cross-tab mutex lock.
   * 1. Prefers the native Web Locks API ('auth_refresh_lock') in supported browsers.
   * 2. If Web Locks API is unavailable, provides a safe localStorage-based mutex fallback to prevent
   *    simultaneous cross-tab refresh token rotation and accidental reuse detection.
   * 3. The fallback only stores an ephemeral lock ID and timestamp (never tokens, credentials, or cookies).
   *
   * @param {Function} callback - Async function executed while holding the cross-tab lock
   * @returns {Promise<any>}
   */
  async function _acquireCrossTabLock(callback) {
    const nav = (typeof window !== 'undefined' && window.navigator) ? window.navigator : (typeof navigator !== 'undefined' ? navigator : null);

    // 1. Standard Web Locks API (Chrome 69+, Edge 79+, Firefox 96+, Safari 15.4+)
    if (nav && nav.locks && typeof nav.locks.request === 'function') {
      return await nav.locks.request('auth_refresh_lock', callback);
    }

    // 2. Safe cross-tab fallback lock using ephemeral localStorage mutex for unsupported browsers
    if (typeof window !== 'undefined' && window.localStorage && typeof window.localStorage.getItem === 'function') {
      const lockKey = 'auth_refresh_lock_ephemeral';
      const lockTimeoutMs = 5000; // 5-second lease prevents deadlocks if a tab is terminated
      const lockId = Math.random().toString(36).substring(2) + '_' + Date.now();
      const startTime = Date.now();
      const maxWaitTimeMs = 8000;

      while (Date.now() - startTime < maxWaitTimeMs) {
        let isFree = false;
        try {
          const raw = window.localStorage.getItem(lockKey);
          if (!raw) {
            isFree = true;
          } else {
            const parsed = JSON.parse(raw);
            if (!parsed.time || Date.now() - parsed.time > lockTimeoutMs) {
              isFree = true; // Stale lock expired
            }
          }
        } catch (e) {
          isFree = true;
        }

        if (isFree) {
          try {
            window.localStorage.setItem(lockKey, JSON.stringify({ id: lockId, time: Date.now() }));
            // Small pause to detect race conditions in storage write
            await new Promise(r => setTimeout(r, 20));
            const confirmRaw = window.localStorage.getItem(lockKey);
            if (confirmRaw) {
              const confirmLock = JSON.parse(confirmRaw);
              if (confirmLock.id === lockId) {
                // Mutex acquired!
                try {
                  return await callback();
                } finally {
                  // Release lock
                  try {
                    const currentRaw = window.localStorage.getItem(lockKey);
                    if (currentRaw && JSON.parse(currentRaw).id === lockId) {
                      window.localStorage.removeItem(lockKey);
                    }
                  } catch (e) {}
                }
              }
            }
          } catch (storageErr) {
            // If localStorage throws (e.g. SecurityError in iframe), proceed directly to callback
            return await callback();
          }
        }

        // Wait with jitter before next poll attempt
        await new Promise(r => setTimeout(r, 30 + Math.random() * 30));
      }
    }

    // Single-tab execution fallback if neither Web Locks nor localStorage is accessible
    return await callback();
  }

  /**
   * Coordinates access-token refresh:
   * 1. Enforces single-flight within the current tab via a shared Promise.
   * 2. Coordinates across browser tabs via Web Locks API ('auth_refresh_lock') or safe mutex fallback.
   * 3. Rechecks session state after acquiring lock:
   *    - If this tab's in-memory token is already valid (and not a forced 401), reuses it.
   *    - If another refresh completed in this tab while waiting, reuses the new token.
   *    - Otherwise, obtains a new access token through the shared HttpOnly cookie.
   * 4. Differentiates network errors from authentication rejections.
   *
   * @param {boolean} [force=false] - When true (e.g. on 401 rejection), forces refresh unless completed after request started
   * @returns {Promise<string>} The newly acquired access token
   */
  async function refreshToken(force = false) {
    const requestTime = Date.now();

    // 1. Single-flight within the current tab
    if (_activeRefreshPromise) {
      return _activeRefreshPromise;
    }

    _activeRefreshPromise = (async () => {
      try {
        return await _acquireCrossTabLock(async () => {
          // Recheck session state after acquiring the lock:
          // 1. If this tab's in-memory token is still valid and this was not a forced 401 refresh,
          //    safely continue using its own still-valid access token.
          if (!force && isTokenValid()) {
            return _accessToken;
          }

          // 2. If this was a forced refresh and another call in this tab refreshed since this request started,
          //    reuse the newly obtained token.
          if (force && _lastRefreshedAt > requestTime && isTokenValid()) {
            return _accessToken;
          }

          // 3. Otherwise (token expired, absent, or forced), obtain a new access token
          //    via the shared HttpOnly cookie. Never assume another tab's in-memory token is received.
          return await _executeRefreshNetworkCall();
        });
      } finally {
        _activeRefreshPromise = null;
      }
    })();

    return _activeRefreshPromise;
  }

  /**
   * Attempts session restoration on page reload or application bootstrap.
   * Checks local signed-out tracking state first; if signed out, never auto-restores.
   * Calls refreshToken() using the HttpOnly cookie.
   * If successful, returns the restored UserResponseDto.
   * If unauthenticated (401/403), clears memory and returns null.
   * If network error, throws network error so caller can display connectivity alert.
   *
   * @returns {Promise<object|null>} Authenticated user profile or null
   */
  async function restoreSession() {
    // If the user has explicitly signed out (or has a pending unconfirmed logout),
    // DO NOT attempt automatic session restoration with lingering cookies!
    if (isLocallySignedOut()) {
      clearAuth();
      return null;
    }

    if (isTokenValid() && _currentUser) {
      return getCurrentUser();
    }

    try {
      await refreshToken();
      return getCurrentUser();
    } catch (err) {
      if (err.isNetworkError) {
        // Temporary network outage - DO NOT clear session or redirect to login
        throw err;
      }
      // Auth error (no cookie / expired cookie)
      clearAuth();
      return null;
    }
  }

  /**
   * Centralized authentication guard for protected application pages (Task 1).
   * Ensures every protected page verifies session validity before displaying content.
   * Prevents exposing protected views while session restoration is pending.
   *
   * @param {object} [options={}]
   * @param {string} [options.redirectUrl='login.html'] - Destination if unauthenticated
   * @param {Function} [options.onSuccess] - Callback when user is verified (e.g. reveal UI, load data)
   * @param {Function} [options.onNetworkError] - Callback when network outage occurs (shows retry UI)
   * @returns {Promise<object|null>} The authenticated user profile or null
   */
  async function requireAuth(options = {}) {
    const redirectUrl = options.redirectUrl || 'login.html';

    try {
      const user = await restoreSession();
      if (user) {
        if (typeof options.onSuccess === 'function') {
          options.onSuccess(user);
        }
        return user;
      }
      // Unauthenticated or locally signed out: clear memory and redirect to login
      clearAuth();
      if (typeof window !== 'undefined' && window.location) {
        window.location.href = redirectUrl;
      }
      return null;
    } catch (err) {
      if (err.isNetworkError) {
        // Network outage: do NOT clear session or redirect to login
        if (typeof options.onNetworkError === 'function') {
          options.onNetworkError(err);
        }
        throw err;
      }
      // Auth failure (token revoked or invalid)
      clearAuth();
      if (typeof window !== 'undefined' && window.location) {
        window.location.href = redirectUrl;
      }
      return null;
    }
  }

  /**
   * Centralized guard for public pages (e.g. login.html, register.html).
   * If the user already has an active, valid session, redirects them to the authenticated dashboard.
   * If the user is locally signed out, stays on the public page without querying backend.
   *
   * @param {string} [destination='index.html'] - Where to redirect if already authenticated
   * @returns {Promise<boolean>} True if redirected, false if unauthenticated
   */
  async function redirectIfAuthenticated(destination = 'index.html') {
    if (isLocallySignedOut()) {
      return false;
    }

    try {
      const user = await restoreSession();
      if (user) {
        if (typeof window !== 'undefined' && window.location) {
          window.location.href = destination;
        }
        return true;
      }
      return false;
    } catch (err) {
      // Unauthenticated or network error on public page: remain on public page
      return false;
    }
  }

  /**
   * Authenticates user credentials against POST /api/v1/auth/login.
   * Deliberate login clears any pending local signed-out state!
   *
   * @param {string} email - Recruiter email address
   * @param {string} password - Raw password (never logged or stored)
   * @returns {Promise<{ accessToken: string, tokenType: string, expiresIn: number, user: object }>}
   */
  async function login(email, password) {
    if (!email || typeof email !== 'string' || !email.trim()) {
      throw new Error('Please enter a valid email address.');
    }
    if (!password || typeof password !== 'string') {
      throw new Error('Please enter your password.');
    }

    const baseUrl = getApiBaseUrl();
    const url = `${baseUrl}/api/v1/auth/login`;

    let response;
    try {
      response = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json'
        },
        credentials: 'include', // Delivers HttpOnly refresh-token cookie
        body: JSON.stringify({
          email: email.trim(),
          password: password
        })
      });
    } catch (networkErr) {
      const err = new Error(
        `Unable to reach backend service at ${baseUrl}. Please ensure the server is running.`
      );
      err.isNetworkError = true;
      throw err;
    }

    let responseData = null;
    try {
      const contentType = response.headers.get('content-type') || '';
      if (contentType.includes('application/json')) {
        responseData = await response.json();
      }
    } catch (e) {
      // Non-JSON response
    }

    if (!response.ok) {
      let errorMessage = 'Authentication failed. Please check your credentials.';
      if (response.status === 401) {
        errorMessage = 'Invalid email or password.';
      } else if (response.status === 403) {
        errorMessage = 'Your account is inactive or disabled. Please contact administrator.';
      } else if (responseData && responseData.message) {
        errorMessage = responseData.message;
      }
      const err = new Error(errorMessage);
      err.status = response.status;
      err.data = responseData;
      throw err;
    }

    if (!responseData || !responseData.accessToken) {
      throw new Error('Invalid response from server: Missing access token.');
    }

    // Deliberate user authentication clears any pending signed-out state!
    clearSignedOutState();

    // Store access token and user profile STRICTLY in memory
    setAuthData(responseData);

    return responseData;
  }

  /**
   * Registers a new recruiter account against POST /api/v1/auth/register.
   *
   * @param {object} registrationData
   * @param {string} registrationData.email
   * @param {string} registrationData.password
   * @param {string} registrationData.firstName
   * @param {string} registrationData.lastName
   * @returns {Promise<object>} Created user profile
   */
  async function register({ email, password, firstName, lastName }) {
    if (!firstName || !firstName.trim()) {
      throw new Error('First name is required.');
    }
    if (!lastName || !lastName.trim()) {
      throw new Error('Last name is required.');
    }
    if (!email || !email.trim()) {
      throw new Error('Email address is required.');
    }
    if (!password) {
      throw new Error('Password is required.');
    }
    if (password.length < 8) {
      throw new Error('Password must be at least 8 characters long.');
    }
    // BCrypt max limit check
    const utf8Length = new TextEncoder().encode(password).length;
    if (utf8Length > 72) {
      throw new Error('Password cannot exceed 72 bytes.');
    }

    const baseUrl = getApiBaseUrl();
    const url = `${baseUrl}/api/v1/auth/register`;

    let response;
    try {
      response = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify({
          email: email.trim(),
          password: password,
          firstName: firstName.trim(),
          lastName: lastName.trim()
        })
      });
    } catch (networkErr) {
      const err = new Error(
        `Unable to reach backend service at ${baseUrl}. Please ensure the server is running.`
      );
      err.isNetworkError = true;
      throw err;
    }

    let responseData = null;
    try {
      const contentType = response.headers.get('content-type') || '';
      if (contentType.includes('application/json')) {
        responseData = await response.json();
      }
    } catch (e) {
      // Non-JSON response
    }

    if (!response.ok) {
      let errorMessage = 'Registration failed. Please check your information.';
      if (response.status === 409) {
        errorMessage = 'An account with this email address already exists. Please sign in or use another email.';
      } else if (response.status === 400) {
        if (responseData && Array.isArray(responseData.errors) && responseData.errors.length > 0) {
          errorMessage = responseData.errors
            .map(err => err.defaultMessage || err.message || JSON.stringify(err))
            .join('; ');
        } else if (responseData && responseData.message) {
          errorMessage = responseData.message;
        } else {
          errorMessage = 'Invalid registration details. Please verify your inputs.';
        }
      } else if (responseData && responseData.message) {
        errorMessage = responseData.message;
      }
      const err = new Error(errorMessage);
      err.status = response.status;
      err.data = responseData;
      throw err;
    }

    return responseData;
  }

  /**
   * Logs out the user.
   * If the backend is reachable, revokes the refresh token session on the server and clears the cookie.
   * If unreachable/offline, records local signed-out state so session cannot be auto-restored.
   * Notifies other tabs via safe BroadcastChannel and fallback storage event.
   *
   * @returns {Promise<boolean>} True if server confirmed revocation; false if logged out locally only
   */
  async function logout() {
    const baseUrl = getApiBaseUrl();
    const url = `${baseUrl}/api/v1/auth/logout`;
    let serverRevoked = false;

    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: {
          'X-Requested-With': 'XMLHttpRequest', // Required by CSRF protection filter
          'Accept': 'application/json'
        },
        credentials: 'include'
      });
      if (response && response.ok) {
        serverRevoked = true;
      }
    } catch (e) {
      console.warn('Logout network notification failed; recorded local signed-out state:', e);
      serverRevoked = false;
    } finally {
      clearAuth();

      // Record local signed-out state.
      // Explicitly tracks whether server confirmed revocation; never falsely claims success when offline.
      setSignedOutState(serverRevoked);

      // 1. Notify other tabs via safe BroadcastChannel (never broadcasts tokens or cookies)
      if (_authChannel) {
        try {
          _authChannel.postMessage({
            type: 'LOGOUT_COMPLETED',
            serverRevoked: serverRevoked,
            timestamp: Date.now()
          });
        } catch (ignored) {}
      }

      // 2. Safe fallback notification for browsers/contexts without BroadcastChannel
      if (typeof window !== 'undefined' && window.localStorage && typeof window.localStorage.setItem === 'function') {
        try {
          window.localStorage.setItem('auth_logout_sync', JSON.stringify({
            serverRevoked: serverRevoked,
            timestamp: Date.now()
          }));
        } catch (ignored) {}
      }

      // 3. Redirect to login.html with status parameter
      if (typeof window !== 'undefined' && window.location && window.location.pathname && !window.location.pathname.endsWith('login.html')) {
        const query = serverRevoked ? 'logout_status=success' : 'logout_status=offline';
        window.location.href = `login.html?${query}`;
      }
    }
    return serverRevoked;
  }

  // Public Module API
  const authModule = {
    getAccessToken,
    setAccessToken,
    getCurrentUser,
    setCurrentUser,
    isAuthenticated,
    isTokenValid,
    isLocallySignedOut,
    getSignedOutState,
    clearAuth,
    login,
    register,
    logout,
    refreshToken,
    restoreSession,
    requireAuth,
    redirectIfAuthenticated
  };

  window.auth = authModule;

})(typeof window !== 'undefined' ? window : this);
