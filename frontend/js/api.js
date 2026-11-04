/**
 * Intelligent Resume Screening System - API Client Module
 *
 * Provides vanilla JavaScript asynchronous API functions to communicate with
 * the Spring Boot backend service running at http://localhost:8080.
 *
 * Endpoints verified from:
 * - com.system.resume.controller.HealthController
 * - com.system.resume.controller.JobDescriptionController
 * - com.system.resume.controller.CandidateRankingController
 * - com.system.resume.controller.ScreeningController
 */

/**
 * Backend service base URL.
 * @type {string}
 */
function resolveBaseUrl() {
  if (typeof window !== 'undefined') {
    // 1. Explicit application config override
    if (window.__APP_CONFIG__ && window.__APP_CONFIG__.API_BASE_URL) {
      return String(window.__APP_CONFIG__.API_BASE_URL).trim().replace(/\/+$/, '');
    }

    // 2. localStorage override, if valid and not a stale loopback mismatch
    try {
      if (window.localStorage && typeof window.localStorage.getItem === 'function') {
        const stored = window.localStorage.getItem('API_BASE_URL');
        if (stored && stored.trim()) {
          const trimmed = stored.trim().replace(/\/+$/, '');
          let isValid = false;
          try {
            const parsed = new URL(trimmed);
            const currentHost = (window.location && window.location.hostname) ? window.location.hostname : '';
            // Detect stale loopback mismatch (e.g. browsing 127.0.0.1 but stored is localhost, or vice-versa)
            const isStaleLoopbackMismatch = currentHost && (
              (currentHost === '127.0.0.1' && parsed.hostname === 'localhost') ||
              (currentHost === 'localhost' && parsed.hostname === '127.0.0.1')
            );

            if (isStaleLoopbackMismatch) {
              window.localStorage.removeItem('API_BASE_URL');
            } else if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
              isValid = true;
            }
          } catch (urlErr) {
            // Malformed URL in localStorage, remove corrupted key
            window.localStorage.removeItem('API_BASE_URL');
          }

          if (isValid) {
            return trimmed;
          }
        }
      }
    } catch (e) {
      // Ignore localStorage access restrictions in sandboxed environments
    }

    // 3. Derive backend base URL from current window location protocol & hostname with port 8080
    if (window.location && window.location.hostname) {
      const protocol = window.location.protocol || 'http:';
      const hostname = window.location.hostname;
      return `${protocol}//${hostname}:8080`;
    }
  }

  // 4. Default fallback for non-browser/SSR/test environments
  return 'http://localhost:8080';
}

let BASE_URL = resolveBaseUrl();

function getBaseUrl() {
  return BASE_URL;
}

function setBaseUrl(newUrl, persist = false) {
  if (!newUrl || typeof newUrl !== 'string') {
    throw new Error('API Base URL must be a non-empty string.');
  }
  BASE_URL = newUrl.trim().replace(/\/+$/, '');
  if (persist && typeof window !== 'undefined') {
    try {
      window.localStorage.setItem('API_BASE_URL', BASE_URL);
    } catch (e) {
      console.warn('Unable to persist API_BASE_URL to localStorage:', e);
    }
  }
  return BASE_URL;
}

/**
 * Internal HTTP request dispatcher using native fetch().
 * Automatically attaches JSON headers, serializes payloads, extracts
 * backend error details, and formats network failures clearly.
 *
 * @param {string} endpoint - Path and query string (e.g., '/api/v1/jobs')
 * @param {RequestInit} [options={}] - Fetch configuration options
 * @returns {Promise<any>} Parsed JSON or text payload
 */
async function request(endpoint, options = {}) {
  const url = `${BASE_URL}${endpoint}`;
  const defaultHeaders = {
    'Accept': 'application/json'
  };

  if (options.body && typeof options.body === 'string') {
    defaultHeaders['Content-Type'] = 'application/json';
  }

  // Attach in-memory JWT access token if authenticated
  if (typeof window !== "undefined" && window.auth && typeof window.auth.getAccessToken === "function") {
    const token = window.auth.getAccessToken();
    if (token && !defaultHeaders["Authorization"]) {
      defaultHeaders["Authorization"] = "Bearer " + token;
    }
  }

  const config = {
    credentials: "include",
    ...options,
    headers: {
      ...defaultHeaders,
      ...(options.headers || {})
    }
  };

  let response;
  try {
    response = await fetch(url, config);
  } catch (networkError) {
    const message = `Network connection failed: Unable to reach backend server at ${BASE_URL}. ` +
      `Please ensure the Spring Boot application is running and accessible on port 8080.`;
    const error = new Error(message);
    error.isNetworkError = true;
    error.originalError = networkError;
    throw error;
  }

  // Handle successful HTTP responses (200-299)
  if (response.ok) {
    if (response.status === 204) {
      return null;
    }
    const contentType = response.headers.get('content-type') || '';
    if (contentType.includes('application/json')) {
      return await response.json();
    }
    return await response.text();
  }

  // Handle HTTP 401 Unauthorized for expired access tokens (Task 2: Automatic Token Refresh)
  if (response.status === 401) {
    const isAuthEndpoint = endpoint.includes('/auth/login') ||
                           endpoint.includes('/auth/register') ||
                           endpoint.includes('/auth/refresh') ||
                           endpoint.includes('/auth/logout');

    if (!isAuthEndpoint && !options._isRetry) {
      if (typeof window !== 'undefined' && window.auth && typeof window.auth.refreshToken === 'function') {
        try {
          const newAccessToken = await window.auth.refreshToken(true);
          if (newAccessToken) {
            // Retry the original request exactly once with the refreshed access token
            const retryHeaders = {
              ...(options.headers || {}),
              'Authorization': 'Bearer ' + newAccessToken
            };
            return await request(endpoint, {
              ...options,
              headers: retryHeaders,
              _isRetry: true // Prevent infinite loops
            });
          }
        } catch (refreshErr) {
          if (refreshErr.isNetworkError) {
            // Preserve session on temporary network outage
            throw refreshErr;
          }
          // Refresh rejected (expired/revoked refresh token): clear auth and redirect
          if (typeof window.auth.clearAuth === 'function') {
            window.auth.clearAuth();
          }
          if (typeof window !== 'undefined' && window.location && !window.location.pathname.endsWith('login.html')) {
            window.location.href = 'login.html';
          }
          throw refreshErr;
        }
      }
    } else if (options._isRetry) {
      // Retry attempt still returned 401: clear session and redirect
      if (typeof window !== 'undefined' && window.auth && typeof window.auth.clearAuth === 'function') {
        window.auth.clearAuth();
      }
      if (typeof window !== 'undefined' && window.location && !window.location.pathname.endsWith('login.html')) {
        window.location.href = 'login.html';
      }
    }
  }

  // Handle HTTP error responses (4xx, 5xx)
  let errorMessage = `HTTP ${response.status}: ${response.statusText || 'Request failed'}`;
  let errorData = null;

  try {
    const contentType = response.headers.get('content-type') || '';
    if (contentType.includes('application/json')) {
      errorData = await response.json();
      if (errorData) {
        if (errorData.message && typeof errorData.message === 'string') {
          errorMessage = errorData.message;
        } else if (Array.isArray(errorData.errors) && errorData.errors.length > 0) {
          errorMessage = errorData.errors
            .map(err => err.defaultMessage || err.message || JSON.stringify(err))
            .join('; ');
        } else if (errorData.error && typeof errorData.error === 'string') {
          errorMessage = errorData.error;
        }
      }
    } else {
      const text = await response.text();
      if (text && text.trim().length > 0) {
        errorMessage = text.length > 250 ? text.substring(0, 250) + '...' : text;
      }
    }
  } catch (parseError) {
    // If parsing response body fails, retain status-based error message
  }

  const httpError = new Error(errorMessage);
  httpError.status = response.status;
  httpError.statusText = response.statusText;
  httpError.data = errorData;
  throw httpError;
}

/**
 * 1. Checks backend service health and liveness.
 * Endpoint: GET /health
 *
 * @returns {Promise<{ status: string, service: string }>} Backend health status
 */
async function checkHealth() {
  return await request('/health');
}

/**
 * Verification check for Python AI service connectivity via backend.
 * Endpoint: GET /health/ai
 *
 * @returns {Promise<{ aiServiceStatus: string, endpoint: string }>} AI service health status
 */
async function checkAiHealth() {
  return await request('/health/ai');
}

/**
 * 2. Fetches all job descriptions with optional department and status filtering.
 * Endpoint: GET /api/v1/jobs
 *
 * @param {Object|string} [filterOrDept] - Filter object { department, status } or department name string
 * @param {string} [status] - Optional status string if department passed as first argument
 * @returns {Promise<Array<Object>>} List of JobDescriptionResponseDto objects
 */
async function getJobs(filterOrDept = {}, status = null) {
  const params = new URLSearchParams();

  if (typeof filterOrDept === 'object' && filterOrDept !== null) {
    if (filterOrDept.department) params.append('department', filterOrDept.department);
    if (filterOrDept.status) params.append('status', filterOrDept.status);
  } else {
    if (filterOrDept) params.append('department', filterOrDept);
    if (status) params.append('status', status);
  }

  const queryString = params.toString() ? `?${params.toString()}` : '';
  return await request(`/api/v1/jobs${queryString}`);
}

/**
 * Fetches a single job description by ID.
 * Endpoint: GET /api/v1/jobs/{id}
 *
 * @param {number|string} jobId - Target job description ID
 * @returns {Promise<Object>} JobDescriptionResponseDto
 */
async function getJobById(jobId) {
  if (!jobId) {
    throw new Error('Job ID is required to fetch job details.');
  }
  return await request(`/api/v1/jobs/${encodeURIComponent(jobId)}`);
}

/**
 * Extracts required skills from raw job description text via the Python AI microservice.
 * Endpoint: POST /api/v1/jobs/extract-skills
 *
 * @param {string} descriptionText - Raw job posting text
 * @returns {Promise<{ skills: Array<string>, wordCount: number, success: boolean, message: string }>}
 */
async function extractJobSkills(descriptionText) {
  if (!descriptionText || typeof descriptionText !== 'string' || !descriptionText.trim()) {
    throw new Error('Job description text cannot be blank.');
  }
  return await request('/api/v1/jobs/extract-skills', {
    method: 'POST',
    body: JSON.stringify({ descriptionText: descriptionText.trim() })
  });
}

/**
 * Creates a new job posting with required skills, experience thresholds, and status.
 * Endpoint: POST /api/v1/jobs
 *
 * @param {Object} jobData - Job creation payload { title, department, location, descriptionText, requiredSkills, minExperienceYears, status }
 * @returns {Promise<Object>} JobDescriptionResponseDto
 */
async function createJob(jobData) {
  if (!jobData || typeof jobData !== 'object') {
    throw new Error('Job data object is required.');
  }
  if (!jobData.title || !jobData.title.trim()) {
    throw new Error('Job title is required.');
  }
  if (!jobData.descriptionText || !jobData.descriptionText.trim()) {
    throw new Error('Job description text is required.');
  }
  return await request('/api/v1/jobs', {
    method: 'POST',
    body: JSON.stringify(jobData)
  });
}

/**
 * Updates an existing job posting.
 * Endpoint: PUT /api/v1/jobs/{id}
 *
 * @param {number|string} jobId - Target job description ID
 * @param {Object} jobData - Job update payload
 * @returns {Promise<Object>} JobDescriptionResponseDto
 */
async function updateJob(jobId, jobData) {
  if (!jobId) {
    throw new Error('Job ID is required to update job.');
  }
  if (!jobData || typeof jobData !== 'object') {
    throw new Error('Job data object is required.');
  }
  return await request(`/api/v1/jobs/${encodeURIComponent(jobId)}`, {
    method: 'PUT',
    body: JSON.stringify(jobData)
  });
}

/**
 * 3. Batch generates and ranks candidates for a job posting.
 * Applies deterministic ranking, automated Top-N quota selection against
 * the score floor, generates explainable AI decision reasons, and preserves
 * prior recruiter overrides.
 *
 * Endpoint: POST /api/v1/rankings/generate/{jobId}?topN={topN}&minScore={minScore}
 *
 * @param {number|string} jobId - Target job description ID (required)
 * @param {number} [topN=10] - Number of candidates to select (defaults to 10, min 1)
 * @param {number} [minScore=20.0] - Minimum match score percentage (defaults to 20.0, range 0-100)
 * @returns {Promise<Array<Object>>} List of CandidateRankingResponseDto objects
 */
async function generateRankings(jobId, topN = 10, minScore = 20.0) {
  if (!jobId) {
    throw new Error('Target Job ID is required to generate candidate rankings.');
  }

  const params = new URLSearchParams();
  if (topN !== undefined && topN !== null) {
    params.append('topN', topN);
  }
  if (minScore !== undefined && minScore !== null) {
    params.append('minScore', minScore);
  }

  const queryString = params.toString() ? `?${params.toString()}` : '';
  return await request(`/api/v1/rankings/generate/${encodeURIComponent(jobId)}${queryString}`, {
    method: 'POST'
  });
}

/**
 * 4. Retrieves the candidate ranking leaderboard for a job, optionally filtered by review status.
 * Endpoint: GET /api/v1/rankings/job/{jobId}?reviewStatus={reviewStatus}
 *
 * @param {number|string} jobId - Target job description ID (required)
 * @param {string} [reviewStatus] - Optional status filter: 'SELECTED' | 'SHORTLISTED' | 'REVIEWED' | 'REJECTED'
 * @returns {Promise<Array<Object>>} List of CandidateRankingResponseDto objects
 */
async function getRankingsByJob(jobId, reviewStatus = null) {
  if (!jobId) {
    throw new Error('Target Job ID is required to fetch rankings.');
  }

  const params = new URLSearchParams();
  if (reviewStatus) {
    params.append('reviewStatus', reviewStatus);
  }

  const queryString = params.toString() ? `?${params.toString()}` : '';
  return await request(`/api/v1/rankings/job/${encodeURIComponent(jobId)}${queryString}`);
}

/**
 * Alias for getRankingsByJob for backward compatibility.
 *
 * @param {number|string} jobId
 * @param {string} [reviewStatus]
 * @returns {Promise<Array<Object>>}
 */
async function getLeaderboardByJob(jobId, reviewStatus = null) {
  return await getRankingsByJob(jobId, reviewStatus);
}

/**
 * 5. Updates recruiter review status and feedback notes for a candidate ranking record.
 * Sets isManualOverride to true on the backend.
 *
 * Endpoint: PATCH /api/v1/rankings/{rankingId}/review
 *
 * @param {number|string} rankingId - ID of candidate ranking record (required)
 * @param {string} reviewStatus - Status: 'SELECTED' | 'SHORTLISTED' | 'REVIEWED' | 'REJECTED' (required)
 * @param {string} [reviewerNotes=''] - Optional recruiter notes/justification
 * @returns {Promise<Object>} Updated CandidateRankingResponseDto
 */
async function updateCandidateReview(rankingId, reviewStatus, reviewerNotes = '') {
  if (!rankingId) {
    throw new Error('Candidate Ranking ID is required to update review status.');
  }
  if (!reviewStatus) {
    throw new Error("Review status is required ('SELECTED', 'SHORTLISTED', 'REVIEWED', or 'REJECTED').");
  }

  const payload = {
    reviewStatus: reviewStatus.trim().toUpperCase(),
    reviewerNotes: reviewerNotes && reviewerNotes.trim().length > 0 ? reviewerNotes.trim() : null
  };

  return await request(`/api/v1/rankings/${encodeURIComponent(rankingId)}/review`, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  });
}

/**
 * Retrieves detailed semantic screening evaluation by screening result ID.
 * Endpoint: GET /api/v1/screening/{id}
 *
 * @param {number|string} screeningResultId - Screening result ID
 * @returns {Promise<Object>} ScreeningResponseDto
 */
async function getScreeningResultById(screeningResultId) {
  if (!screeningResultId) {
    throw new Error('Screening Result ID is required.');
  }
  return await request(`/api/v1/screening/${encodeURIComponent(screeningResultId)}`);
}

/**
 * 6. Uploads a batch of resume files (up to 100) for parsing, candidate linking, and optional screening.
 * Endpoint: POST /api/v1/resumes/batches
 *
 * @param {FormData} formData - FormData containing 'files', optional 'batchName', optional 'jobDescriptionId'
 * @returns {Promise<Object>} ResumeBatchDetailResponseDto (batch summary and file outcomes)
 */
async function uploadBatch(formData) {
  if (!formData || !(formData instanceof FormData)) {
    throw new Error('A valid FormData object containing resume files is required.');
  }

  return await request('/api/v1/resumes/batches', {
    method: 'POST',
    body: formData
  });
}

/**
 * 7. Retrieves all resume batches ordered by creation date descending.
 * Endpoint: GET /api/v1/resumes/batches
 *
 * @returns {Promise<Array<Object>>} List of ResumeBatchResponseDto objects
 */
async function getBatches() {
  return await request('/api/v1/resumes/batches');
}

/**
 * 8. Retrieves comprehensive batch details including summary and all individual file outcomes.
 * Endpoint: GET /api/v1/resumes/batches/{id}
 *
 * @param {number|string} id - Target batch ID (required)
 * @returns {Promise<Object>} ResumeBatchDetailResponseDto
 */
async function getBatchById(id) {
  if (!id) {
    throw new Error('Batch ID is required to fetch batch details.');
  }
  return await request(`/api/v1/resumes/batches/${encodeURIComponent(id)}`);
}

/**
 * 9. Retrieves lightweight batch progress summary (totalFiles, processedFiles, status).
 * Endpoint: GET /api/v1/resumes/batches/{id}/status
 *
 * @param {number|string} id - Target batch ID (required)
 * @returns {Promise<Object>} ResumeBatchResponseDto
 */
async function getBatchStatus(id) {
  if (!id) {
    throw new Error('Batch ID is required to fetch batch status.');
  }
  return await request(`/api/v1/resumes/batches/${encodeURIComponent(id)}/status`);
}

/**
 * 10. Retrieves individual file outcomes, statuses, hashes, and error messages for a batch.
 * Endpoint: GET /api/v1/resumes/batches/{id}/files
 *
 * @param {number|string} id - Target batch ID (required)
 * @returns {Promise<Array<Object>>} List of ResumeBatchFileResponseDto objects
 */
async function getBatchFiles(id) {
  if (!id) {
    throw new Error('Batch ID is required to fetch batch files.');
  }
  return await request(`/api/v1/resumes/batches/${encodeURIComponent(id)}/files`);
}

/**
 * 11. Generates download URL for backend CSV export.
 * Endpoint: GET /api/v1/rankings/job/{jobId}/export
 *
 * @param {number|string} jobId - Target job ID
 * @param {string|null} [reviewStatus=null] - Optional review status filter
 * @returns {string} Download URL
 */
function getExportCsvUrl(jobId, reviewStatus = null) {
  if (!jobId) throw new Error('Job ID is required to export CSV.');
  let url = `${BASE_URL}/api/v1/rankings/job/${encodeURIComponent(jobId)}/export`;
  if (reviewStatus && reviewStatus.toLowerCase() !== 'all') {
    url += `?reviewStatus=${encodeURIComponent(reviewStatus.toUpperCase())}`;
  }
  return url;
}

// Package API surface for browser scripts
const api = {
  get BASE_URL() { return BASE_URL; },
  getBaseUrl,
  setBaseUrl,
  checkHealth,
  checkAiHealth,
  getJobs,
  getJobById,
  extractJobSkills,
  createJob,
  updateJob,
  generateRankings,
  getRankingsByJob,
  getLeaderboardByJob,
  updateCandidateReview,
  getScreeningResultById,
  uploadBatch,
  getBatches,
  getBatchById,
  getBatchStatus,
  getBatchFiles,
  getExportCsvUrl
};

// Expose API module and functions globally for deferred script execution
if (typeof window !== 'undefined') {
  window.api = api;
  window.getBaseUrl = getBaseUrl;
  window.setBaseUrl = setBaseUrl;
  window.checkHealth = checkHealth;
  window.checkAiHealth = checkAiHealth;
  window.getJobs = getJobs;
  window.getJobById = getJobById;
  window.extractJobSkills = extractJobSkills;
  window.createJob = createJob;
  window.updateJob = updateJob;
  window.generateRankings = generateRankings;
  window.getRankingsByJob = getRankingsByJob;
  window.getLeaderboardByJob = getLeaderboardByJob;
  window.updateCandidateReview = updateCandidateReview;
  window.getScreeningResultById = getScreeningResultById;
  window.uploadBatch = uploadBatch;
  window.getBatches = getBatches;
  window.getBatchById = getBatchById;
  window.getBatchStatus = getBatchStatus;
  window.getBatchFiles = getBatchFiles;
  window.getExportCsvUrl = getExportCsvUrl;
}
