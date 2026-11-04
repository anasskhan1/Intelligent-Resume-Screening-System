/**
 * Intelligent Resume Screening System - Recruiter Dashboard Controller
 *
 * Provides vanilla JavaScript client-side application logic:
 * - Application initialization & backend liveness checks
 * - Job descriptions loading & parameter synchronization (Top-N, Min Score)
 * - Deterministic candidate ranking generation & leaderboard presentation
 * - Split-column layout (Selected vs. Rejected) with status & override badges
 * - Tabbed filtering (All, Selected, Rejected, Overrides)
 * - Expandable AI decision rationale with verified skills evaluation
 * - Recruiter decision overrides via PATCH API with instant state preservation
 * - Real-time KPI metric updates and accessible toast notifications
 */

// Application State
const state = {
  activeWorkspace: 'batches', // 'batches' | 'leaderboard'
  jobs: [],
  selectedJobId: null,
  rankings: [],
  currentFilter: 'all', // 'all' | 'selected' | 'rejected' | 'override'
  isGenerating: false,
  screeningCache: new Map(), // screeningResultId -> ScreeningResponseDto

  // Bulk Upload Queue & State (Step 4)
  uploadQueue: [], // Array of { file: File, id: string, name: string, size: number, ext: string, hash: string }
  isUploadingBatch: false,
  uploadTimerInterval: null,
  uploadStartTime: null,
  activePollingBatchId: null,
  pollingInterval: null,
  pollingTargetJobId: null,

  // Batch History & Details (Step 5)
  batches: [],
  selectedBatchId: null,
  selectedBatchDetail: null,
  isLoadingBatches: false,
  isLoadingBatchDetail: false,

  // Recruiter Job Authoring (Phase 4B - Step 1)
  isJobModalOpen: false,
  jobModalSkills: [],
  isExtractingSkills: false,
  isSavingJob: false,

  // Candidate Search & Leaderboard Filtering (Phase 4B - Step 5)
  filters: {
    searchQuery: '',
    minScore: null,
    maxScore: null,
    minExperience: 'any',
    reviewStatus: 'all',
    skillKeyword: '',
    isAdvancedOpen: false
  }
};

// DOM Elements Cache
const elements = {
  // Navigation & Workspace Panes
  navBtnUpload: null,
  navBtnLeaderboard: null,
  workspaceBatches: null,
  workspaceLeaderboard: null,

  // Bulk Upload & Batch Processing (Step 4)
  bulkUploadSection: null,
  batchNameInput: null,
  batchJobSelect: null,
  resumeDropzone: null,
  resumeFileInput: null,
  browseFilesBtn: null,
  queueSection: null,
  queueList: null,
  queueCountBadge: null,
  queueSizeBadge: null,
  clearQueueBtn: null,
  uploadActionInfo: null,
  uploadBatchBtn: null,
  uploadBtnIcon: null,
  uploadBtnText: null,
  uploadProcessingBanner: null,
  processingBannerTitle: null,
  processingBannerDescription: null,
  processingProgressBar: null,
  processingProgressText: null,
  processingProgressStatus: null,
  processingElapsedTimer: null,
  uploadOutcomeCard: null,
  outcomeBatchTitle: null,
  outcomeBatchSubtitle: null,
  outcomeHeaderActions: null,
  outcomeTotalCount: null,
  outcomeParsedCount: null,
  outcomeDuplicateCount: null,
  outcomeFailedCount: null,
  outcomeFilesTbody: null,
  outcomeDismissBtn: null,

  // Batch History & Outcomes View (Step 5)
  batchHistorySection: null,
  batchHistoryListView: null,
  refreshBatchesBtn: null,
  refreshBatchesIcon: null,
  refreshBatchesText: null,
  batchHistoryLoading: null,
  batchHistoryError: null,
  batchHistoryErrorTitle: null,
  batchHistoryErrorMessage: null,
  retryBatchesBtn: null,
  batchHistoryEmpty: null,
  batchHistoryTableContainer: null,
  batchHistoryTbody: null,
  batchDetailView: null,
  batchDetailBackBtn: null,
  batchDetailTitle: null,
  batchDetailSubtitle: null,
  batchDetailActions: null,
  batchDetailRefreshBtn: null,
  batchDetailRefreshIcon: null,
  batchDetailRefreshText: null,
  batchDetailLoading: null,
  batchDetailError: null,
  batchDetailErrorTitle: null,
  batchDetailErrorMessage: null,
  retryBatchDetailBtn: null,
  batchDetailContent: null,
  detailTotalCount: null,
  detailParsedCount: null,
  detailDuplicateCount: null,
  detailFailedCount: null,
  batchDetailFilesTbody: null,

  // Header Status
  backendStatusBadge: null,
  backendStatusDot: null,
  backendStatusText: null,

  // Recruiter Controls
  rankingForm: null,
  jobSelect: null,
  jobCountHint: null,
  topNInput: null,
  minScoreInput: null,
  minScoreValue: null,
  generateBtn: null,
  generateBtnText: null,

  // KPI Metrics
  totalApplicants: null,
  selectedCount: null,
  rejectedCount: null,
  overrideCount: null,

  // Leaderboard & Filter Tabs
  leaderboardTotalBadge: null,
  tabAll: null,
  tabSelected: null,
  tabRejected: null,
  tabOverride: null,
  tabBadgeAll: null,
  tabBadgeSelected: null,
  tabBadgeRejected: null,
  tabBadgeOverride: null,

  // Candidate Search & Advanced Filtering (Phase 4B - Step 5)
  leaderboardFilterToolbar: null,
  candidateSearchInput: null,
  searchClearBtn: null,
  toggleAdvancedFiltersBtn: null,
  activeFilterCountBadge: null,
  clearAllFiltersBtn: null,
  advancedFiltersPanel: null,
  filterMinScore: null,
  filterMaxScore: null,
  filterMinExperience: null,
  filterReviewStatus: null,
  filterSkillKeyword: null,
  filterRangeError: null,
  filterResultsSummary: null,
  filterMatchingCount: null,
  filterTotalCount: null,
  filterChipsList: null,

  // CSV Export Elements (Phase 4B - Step 6)
  exportCsvBtn: null,
  exportCountBadge: null,
  exportDropdownMenu: null,
  exportFilteredBtn: null,
  exportAllBtn: null,
  exportFilteredCountLabel: null,
  exportAllCountLabel: null,

  // View States
  loadingState: null,
  loadingTitle: null,
  loadingMessage: null,
  loadingSubtext: null,
  errorBanner: null,
  errorTitle: null,
  errorMessage: null,
  errorDismissBtn: null,
  emptyState: null,
  emptyStateTitle: null,
  emptyStateDesc: null,
  emptyStateActions: null,

  // Split Columns
  leaderboardContent: null,
  selectedColumn: null,
  selectedColumnBadge: null,
  selectedList: null,
  rejectedColumn: null,
  rejectedColumnBadge: null,
  rejectedList: null,

  // Feedback
  toastContainer: null,

  // Recruiter Job Authoring Modal Elements (Phase 4B - Step 1)
  openJobModalHeaderBtn: null,
  openJobModalBatchBtn: null,
  openJobModalLeaderboardBtn: null,
  jobModalBackdrop: null,
  jobModalCloseBtn: null,
  jobModalCancelBtn: null,
  jobModalForm: null,
  jobModalInputTitle: null,
  jobModalInputDept: null,
  jobModalInputLocation: null,
  jobModalInputMinExp: null,
  jobModalInputStatus: null,
  jobModalInputDesc: null,
  jobModalCharCount: null,
  jobModalDescStats: null,
  jobModalExtractBtn: null,
  jobModalExtractIcon: null,
  jobModalExtractText: null,
  jobModalSkillsCount: null,
  jobModalClearSkillsBtn: null,
  jobModalSkillsWrap: null,
  jobModalSkillsEmptyHint: null,
  jobModalCustomSkillInput: null,
  jobModalAddSkillBtn: null,
  jobModalErrorBanner: null,
  jobModalErrorTitle: null,
  jobModalErrorDesc: null,
  jobModalSaveBtn: null,
  jobModalSaveIcon: null,
  jobModalSaveText: null
};

// Utility: HTML Escaping to prevent XSS
function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

// Utility: Toast Notification Dispatcher
function showToast(message, type = 'info', durationMs = 4000) {
  if (!elements.toastContainer) return;

  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;
  toast.setAttribute('role', 'alert');

  let iconSvg = '';
  if (type === 'success') {
    iconSvg = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="color: var(--color-selected); flex-shrink: 0;"><polyline points="20 6 9 17 4 12"></polyline></svg>`;
  } else if (type === 'error') {
    iconSvg = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="color: var(--color-rejected); flex-shrink: 0;"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>`;
  } else {
    iconSvg = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--color-primary-light); flex-shrink: 0;"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="16" x2="12" y2="12"></line><line x1="12" y1="8" x2="12.01" y2="8"></line></svg>`;
  }

  toast.innerHTML = `
    ${iconSvg}
    <div style="flex: 1; word-break: break-word;">${escapeHtml(message)}</div>
  `;

  elements.toastContainer.appendChild(toast);

  setTimeout(() => {
    toast.style.transition = 'opacity 300ms ease-out, transform 300ms ease-out';
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(8px)';
    setTimeout(() => {
      if (toast.parentNode) {
        toast.parentNode.removeChild(toast);
      }
    }, 320);
  }, durationMs);
}

// Utility: Show / Hide Error Banner
function showErrorBanner(title, message) {
  if (elements.errorTitle) elements.errorTitle.textContent = title;
  if (elements.errorMessage) elements.errorMessage.textContent = message;
  if (elements.errorBanner) elements.errorBanner.style.display = 'flex';
}

function hideErrorBanner() {
  if (elements.errorBanner) elements.errorBanner.style.display = 'none';
}

/**
 * 1. Application Initialization
 */
document.addEventListener('DOMContentLoaded', () => {
  cacheDomElements();
  registerEventListeners();

  // Task 1: Protected Page Authentication Guard
  restoreSessionOnStartup();
});

/**
 * Restores authentication session via centralized requireAuth guard before revealing dashboard.
 * @returns {Promise<boolean>}
 */
async function restoreSessionOnStartup() {
  const overlay = document.getElementById('auth-loading-overlay');
  const loadingStatus = document.getElementById('auth-loading-status');
  const loadingError = document.getElementById('auth-loading-error');
  const loadingErrorMsg = document.getElementById('auth-loading-error-msg');
  const retryBtn = document.getElementById('auth-retry-btn');

  if (typeof window === 'undefined' || !window.auth || typeof window.auth.requireAuth !== 'function') {
    if (overlay) overlay.style.display = 'none';
    return true;
  }

  try {
    const user = await window.auth.requireAuth({
      redirectUrl: 'login.html',
      onSuccess: () => {
        hideAuthOverlay();
        syncUserProfileUI();
        checkBackendHealth();
        loadJobs();
        loadBatches(false);
      },
      onNetworkError: (err) => {
        if (loadingStatus) loadingStatus.style.display = 'none';
        if (loadingErrorMsg) loadingErrorMsg.textContent = err.message || 'Unable to connect to server.';
        if (loadingError) loadingError.style.display = 'flex';
        if (retryBtn) {
          retryBtn.onclick = () => {
            if (loadingStatus) loadingStatus.style.display = 'flex';
            if (loadingError) loadingError.style.display = 'none';
            restoreSessionOnStartup();
          };
        }
      }
    });
    return Boolean(user);
  } catch (err) {
    return false;
  }
}

/**
 * Hides the splash overlay with a smooth transition once session is verified.
 */
function hideAuthOverlay() {
  const overlay = document.getElementById('auth-loading-overlay');
  if (overlay) {
    overlay.style.opacity = '0';
    overlay.style.transition = 'opacity 200ms ease-out';
    setTimeout(() => {
      overlay.style.display = 'none';
    }, 200);
  }
}

/**
 * Synchronizes in-memory authenticated user state with the header profile widget.
 */
function syncUserProfileUI() {
  if (typeof window === 'undefined' || !window.auth) return;
  const user = window.auth.getCurrentUser();
  if (user) {
    if (elements.userDisplayName) {
      elements.userDisplayName.textContent = user.fullName || user.email;
    }
    if (elements.userDisplayRole) {
      const roles = user.roles ? Array.from(user.roles).join(', ') : 'ROLE_RECRUITER';
      elements.userDisplayRole.textContent = roles;
    }
    if (elements.userAvatarInitials) {
      const first = (user.firstName && user.firstName[0]) || '';
      const last = (user.lastName && user.lastName[0]) || '';
      elements.userAvatarInitials.textContent = (first + last).toUpperCase() || 'U';
    }
    if (elements.userAuthWidget) {
      elements.userAuthWidget.style.display = 'flex';
    }
  } else {
    if (elements.userDisplayName) elements.userDisplayName.textContent = 'Recruiter (Guest)';
    if (elements.userDisplayRole) elements.userDisplayRole.textContent = 'ROLE_RECRUITER';
  }
}

/**
 * Cache all target DOM elements using confirmed index.html IDs.
 */
function cacheDomElements() {
  // Navigation & Workspace Panes
  elements.navBtnUpload = document.getElementById('nav-btn-upload');
  elements.navBtnLeaderboard = document.getElementById('nav-btn-leaderboard');
  elements.workspaceBatches = document.getElementById('workspace-batches');
  elements.workspaceLeaderboard = document.getElementById('workspace-leaderboard');

  // Bulk Upload & Batch Processing (Step 4)
  elements.bulkUploadSection = document.getElementById('bulk-upload-section');
  elements.batchNameInput = document.getElementById('batch-name-input');
  elements.batchJobSelect = document.getElementById('batch-job-select');
  elements.resumeDropzone = document.getElementById('resume-dropzone');
  elements.resumeFileInput = document.getElementById('resume-file-input');
  elements.browseFilesBtn = document.getElementById('browse-files-btn');
  elements.queueSection = document.getElementById('queue-section');
  elements.queueList = document.getElementById('queue-list');
  elements.queueCountBadge = document.getElementById('queue-count-badge');
  elements.queueSizeBadge = document.getElementById('queue-size-badge');
  elements.clearQueueBtn = document.getElementById('clear-queue-btn');
  elements.uploadActionInfo = document.getElementById('upload-action-info');
  elements.uploadBatchBtn = document.getElementById('upload-batch-btn');
  elements.uploadBtnIcon = document.getElementById('upload-btn-icon');
  elements.uploadBtnText = document.getElementById('upload-btn-text');
  elements.uploadProcessingBanner = document.getElementById('upload-processing-banner');
  elements.processingBannerTitle = document.getElementById('processing-banner-title');
  elements.processingBannerDescription = document.getElementById('processing-banner-description');
  elements.processingProgressBar = document.getElementById('processing-progress-bar');
  elements.processingProgressText = document.getElementById('processing-progress-text');
  elements.processingProgressStatus = document.getElementById('processing-progress-status');
  elements.processingElapsedTimer = document.getElementById('processing-elapsed-timer');
  elements.uploadOutcomeCard = document.getElementById('upload-outcome-card');
  elements.outcomeBatchTitle = document.getElementById('outcome-batch-title');
  elements.outcomeBatchSubtitle = document.getElementById('outcome-batch-subtitle');
  elements.outcomeHeaderActions = document.getElementById('outcome-header-actions');
  elements.outcomeTotalCount = document.getElementById('outcome-total-count');

  // Authenticated Recruiter Profile & Logout
  elements.userAuthWidget = document.getElementById('user-auth-widget');
  elements.userDisplayName = document.getElementById('user-display-name');
  elements.userDisplayRole = document.getElementById('user-display-role');
  elements.userAvatarInitials = document.getElementById('user-avatar-initials');
  elements.authLogoutBtn = document.getElementById('auth-logout-btn');
  elements.outcomeParsedCount = document.getElementById('outcome-parsed-count');
  elements.outcomeDuplicateCount = document.getElementById('outcome-duplicate-count');
  elements.outcomeFailedCount = document.getElementById('outcome-failed-count');
  elements.outcomeFilesTbody = document.getElementById('outcome-files-tbody');
  elements.outcomeDismissBtn = document.getElementById('outcome-dismiss-btn');

  // Batch History & Outcomes View (Step 5)
  elements.batchHistorySection = document.getElementById('batch-history-section');
  elements.batchHistoryListView = document.getElementById('batch-history-list-view');
  elements.refreshBatchesBtn = document.getElementById('refresh-batches-btn');
  elements.refreshBatchesIcon = document.getElementById('refresh-batches-icon');
  elements.refreshBatchesText = document.getElementById('refresh-batches-text');
  elements.batchHistoryLoading = document.getElementById('batch-history-loading');
  elements.batchHistoryError = document.getElementById('batch-history-error');
  elements.batchHistoryErrorTitle = document.getElementById('batch-history-error-title');
  elements.batchHistoryErrorMessage = document.getElementById('batch-history-error-message');
  elements.retryBatchesBtn = document.getElementById('retry-batches-btn');
  elements.batchHistoryEmpty = document.getElementById('batch-history-empty');
  elements.batchHistoryTableContainer = document.getElementById('batch-history-table-container');
  elements.batchHistoryTbody = document.getElementById('batch-history-tbody');
  elements.batchDetailView = document.getElementById('batch-detail-view');
  elements.batchDetailBackBtn = document.getElementById('batch-detail-back-btn');
  elements.batchDetailTitle = document.getElementById('batch-detail-title');
  elements.batchDetailSubtitle = document.getElementById('batch-detail-subtitle');
  elements.batchDetailActions = document.getElementById('batch-detail-actions');
  elements.batchDetailRefreshBtn = document.getElementById('batch-detail-refresh-btn');
  elements.batchDetailRefreshIcon = document.getElementById('batch-detail-refresh-icon');
  elements.batchDetailRefreshText = document.getElementById('batch-detail-refresh-text');
  elements.batchDetailLoading = document.getElementById('batch-detail-loading');
  elements.batchDetailError = document.getElementById('batch-detail-error');
  elements.batchDetailErrorTitle = document.getElementById('batch-detail-error-title');
  elements.batchDetailErrorMessage = document.getElementById('batch-detail-error-message');
  elements.retryBatchDetailBtn = document.getElementById('retry-batch-detail-btn');
  elements.batchDetailContent = document.getElementById('batch-detail-content');
  elements.detailTotalCount = document.getElementById('detail-total-count');
  elements.detailParsedCount = document.getElementById('detail-parsed-count');
  elements.detailDuplicateCount = document.getElementById('detail-duplicate-count');
  elements.detailFailedCount = document.getElementById('detail-failed-count');
  elements.batchDetailFilesTbody = document.getElementById('batch-detail-files-tbody');

  elements.backendStatusBadge = document.getElementById('backend-status-badge');
  elements.backendStatusDot = document.getElementById('backend-status-dot');
  elements.backendStatusText = document.getElementById('backend-status-text');

  elements.rankingForm = document.getElementById('ranking-form');
  elements.jobSelect = document.getElementById('job-select');
  elements.jobCountHint = document.getElementById('job-count-hint');
  elements.topNInput = document.getElementById('top-n');
  elements.minScoreInput = document.getElementById('min-score');
  elements.minScoreValue = document.getElementById('min-score-value');
  elements.generateBtn = document.getElementById('generate-btn');
  elements.generateBtnText = document.getElementById('generate-btn-text');

  elements.totalApplicants = document.getElementById('total-applicants');
  elements.selectedCount = document.getElementById('selected-count');
  elements.rejectedCount = document.getElementById('rejected-count');
  elements.overrideCount = document.getElementById('override-count');

  elements.leaderboardTotalBadge = document.getElementById('leaderboard-total-badge');
  elements.tabAll = document.getElementById('tab-all');
  elements.tabSelected = document.getElementById('tab-selected');
  elements.tabRejected = document.getElementById('tab-rejected');
  elements.tabOverride = document.getElementById('tab-override');
  elements.tabBadgeAll = document.getElementById('tab-badge-all');
  elements.tabBadgeSelected = document.getElementById('tab-badge-selected');
  elements.tabBadgeRejected = document.getElementById('tab-badge-rejected');
  elements.tabBadgeOverride = document.getElementById('tab-badge-override');

  // Candidate Search & Advanced Filtering (Phase 4B - Step 5)
  elements.leaderboardFilterToolbar = document.getElementById('leaderboard-filter-toolbar');
  elements.candidateSearchInput = document.getElementById('candidate-search-input');
  elements.searchClearBtn = document.getElementById('search-clear-btn');
  elements.toggleAdvancedFiltersBtn = document.getElementById('toggle-advanced-filters-btn');
  elements.activeFilterCountBadge = document.getElementById('active-filter-count-badge');
  elements.clearAllFiltersBtn = document.getElementById('clear-all-filters-btn');
  elements.advancedFiltersPanel = document.getElementById('advanced-filters-panel');
  elements.filterMinScore = document.getElementById('filter-min-score');
  elements.filterMaxScore = document.getElementById('filter-max-score');
  elements.filterMinExperience = document.getElementById('filter-min-experience');
  elements.filterReviewStatus = document.getElementById('filter-review-status');
  elements.filterSkillKeyword = document.getElementById('filter-skill-keyword');
  elements.filterRangeError = document.getElementById('filter-range-error');
  elements.filterResultsSummary = document.getElementById('filter-results-summary');
  elements.filterMatchingCount = document.getElementById('filter-matching-count');
  elements.filterTotalCount = document.getElementById('filter-total-count');
  elements.filterChipsList = document.getElementById('filter-chips-list');

  // CSV Export Elements (Phase 4B - Step 6)
  elements.exportCsvBtn = document.getElementById('export-csv-btn');
  elements.exportCountBadge = document.getElementById('export-count-badge');
  elements.exportDropdownMenu = document.getElementById('export-dropdown-menu');
  elements.exportFilteredBtn = document.getElementById('export-filtered-btn');
  elements.exportAllBtn = document.getElementById('export-all-btn');
  elements.exportFilteredCountLabel = document.getElementById('export-filtered-count-label');
  elements.exportAllCountLabel = document.getElementById('export-all-count-label');

  elements.loadingState = document.getElementById('loading-state');
  elements.loadingTitle = document.getElementById('loading-title');
  elements.loadingMessage = document.getElementById('loading-message');
  elements.loadingSubtext = document.getElementById('loading-subtext');
  elements.errorBanner = document.getElementById('error-banner');
  elements.errorTitle = document.getElementById('error-title');
  elements.errorMessage = document.getElementById('error-message');
  elements.errorDismissBtn = document.getElementById('error-dismiss-btn');
  elements.emptyState = document.getElementById('empty-state');
  elements.emptyStateTitle = document.getElementById('empty-state-title');
  elements.emptyStateDesc = document.getElementById('empty-state-desc');
  elements.emptyStateActions = document.getElementById('empty-state-actions');

  elements.leaderboardContent = document.getElementById('leaderboard-content');
  elements.selectedColumn = document.getElementById('selected-column');
  elements.selectedColumnBadge = document.getElementById('selected-column-badge');
  elements.selectedList = document.getElementById('selected-list');
  elements.rejectedColumn = document.getElementById('rejected-column');
  elements.rejectedColumnBadge = document.getElementById('rejected-column-badge');
  elements.rejectedList = document.getElementById('rejected-list');

  elements.toastContainer = document.getElementById('toast-container');

  // Recruiter Job Authoring Modal Elements (Phase 4B - Step 1)
  elements.openJobModalHeaderBtn = document.getElementById('open-job-modal-header-btn');
  elements.openJobModalBatchBtn = document.getElementById('open-job-modal-batch-btn');
  elements.openJobModalLeaderboardBtn = document.getElementById('open-job-modal-leaderboard-btn');
  elements.jobModalBackdrop = document.getElementById('job-modal-backdrop');
  elements.jobModalCloseBtn = document.getElementById('job-modal-close-btn');
  elements.jobModalCancelBtn = document.getElementById('job-modal-cancel-btn');
  elements.jobModalForm = document.getElementById('job-modal-form');
  elements.jobModalInputTitle = document.getElementById('job-modal-input-title');
  elements.jobModalInputDept = document.getElementById('job-modal-input-dept');
  elements.jobModalInputLocation = document.getElementById('job-modal-input-location');
  elements.jobModalInputMinExp = document.getElementById('job-modal-input-min-exp');
  elements.jobModalInputStatus = document.getElementById('job-modal-input-status');
  elements.jobModalInputDesc = document.getElementById('job-modal-input-desc');
  elements.jobModalCharCount = document.getElementById('job-modal-char-count');
  elements.jobModalDescStats = document.getElementById('job-modal-desc-stats');
  elements.jobModalExtractBtn = document.getElementById('job-modal-extract-btn');
  elements.jobModalExtractIcon = document.getElementById('job-modal-extract-icon');
  elements.jobModalExtractText = document.getElementById('job-modal-extract-text');
  elements.jobModalSkillsCount = document.getElementById('job-modal-skills-count');
  elements.jobModalClearSkillsBtn = document.getElementById('job-modal-clear-skills-btn');
  elements.jobModalSkillsWrap = document.getElementById('job-modal-skills-wrap');
  elements.jobModalSkillsEmptyHint = document.getElementById('job-modal-skills-empty-hint');
  elements.jobModalCustomSkillInput = document.getElementById('job-modal-custom-skill-input');
  elements.jobModalAddSkillBtn = document.getElementById('job-modal-add-skill-btn');
  elements.jobModalErrorBanner = document.getElementById('job-modal-error-banner');
  elements.jobModalErrorTitle = document.getElementById('job-modal-error-title');
  elements.jobModalErrorDesc = document.getElementById('job-modal-error-desc');
  elements.jobModalSaveBtn = document.getElementById('job-modal-save-btn');
  elements.jobModalSaveIcon = document.getElementById('job-modal-save-icon');
  elements.jobModalSaveText = document.getElementById('job-modal-save-text');
}

/**
 * Switch active recruiter dashboard workspace between:
 * - 'batches' (Bulk Upload & Batch Ingestion)
 * - 'leaderboard' (Candidate Ranking & Leaderboard)
 *
 * @param {string} targetId - 'batches' | 'leaderboard'
 */
function switchWorkspace(targetId) {
  if (targetId !== 'batches' && targetId !== 'leaderboard') return;

  state.activeWorkspace = targetId;

  if (targetId === 'batches') {
    // Nav tab active state & ARIA
    if (elements.navBtnUpload) {
      elements.navBtnUpload.classList.add('is-active');
      elements.navBtnUpload.setAttribute('aria-selected', 'true');
    }
    if (elements.navBtnLeaderboard) {
      elements.navBtnLeaderboard.classList.remove('is-active');
      elements.navBtnLeaderboard.setAttribute('aria-selected', 'false');
    }

    // Toggle Workspace Panes
    if (elements.workspaceBatches) {
      elements.workspaceBatches.classList.remove('is-hidden');
    }
    if (elements.workspaceLeaderboard) {
      elements.workspaceLeaderboard.classList.add('is-hidden');
    }
  } else {
    // Nav tab active state & ARIA
    if (elements.navBtnUpload) {
      elements.navBtnUpload.classList.remove('is-active');
      elements.navBtnUpload.setAttribute('aria-selected', 'false');
    }
    if (elements.navBtnLeaderboard) {
      elements.navBtnLeaderboard.classList.add('is-active');
      elements.navBtnLeaderboard.setAttribute('aria-selected', 'true');
    }

    // Toggle Workspace Panes
    if (elements.workspaceBatches) {
      elements.workspaceBatches.classList.add('is-hidden');
    }
    if (elements.workspaceLeaderboard) {
      elements.workspaceLeaderboard.classList.remove('is-hidden');
    }
  }
}

// Expose switchWorkspace globally for programmatic tab navigation
window.switchWorkspace = switchWorkspace;

/**
 * Register all DOM event listeners centrally (strictly no inline JavaScript).
 */
function registerEventListeners() {
  // Authenticated Recruiter Logout
  if (elements.authLogoutBtn) {
    elements.authLogoutBtn.addEventListener('click', async () => {
      if (window.auth && typeof window.auth.logout === 'function') {
        try {
          await window.auth.logout();
        } catch (e) {
          // Ignore network errors during logout
        }
      }
      window.location.href = 'login.html';
    });
  }

  // Workspace Navigation Tabs
  if (elements.navBtnUpload) {
    elements.navBtnUpload.addEventListener('click', () => {
      switchWorkspace('batches');
    });
  }
  if (elements.navBtnLeaderboard) {
    elements.navBtnLeaderboard.addEventListener('click', () => {
      switchWorkspace('leaderboard');
    });
  }

  // Sync Minimum Match Score Slider with percentage text tag
  if (elements.minScoreInput && elements.minScoreValue) {
    elements.minScoreInput.addEventListener('input', () => {
      elements.minScoreValue.textContent = `${elements.minScoreInput.value}%`;
    });
  }

  // Handle Job Dropdown Selection change
  if (elements.jobSelect) {
    elements.jobSelect.addEventListener('change', handleJobSelectionChange);
  }

  // Ensure the Generate Rankings button acts as a form submit button
  if (elements.generateBtn) {
    elements.generateBtn.type = 'submit';
  }

  // Handle Form Submit as the single trigger for ranking generation
  if (elements.rankingForm) {
    elements.rankingForm.addEventListener('submit', (e) => {
      e.preventDefault();
      handleGenerateRankings();
    });
  }

  // Handle Error Dismiss Button Click
  if (elements.errorDismissBtn) {
    elements.errorDismissBtn.addEventListener('click', () => {
      hideErrorBanner();
    });
  }

  // Handle Filter Tabs Click
  const tabPills = [elements.tabAll, elements.tabSelected, elements.tabRejected, elements.tabOverride];
  tabPills.forEach(tab => {
    if (tab) {
      tab.addEventListener('click', () => {
        const filter = tab.dataset.filter || 'all';
        setActiveFilterTab(filter);
      });
    }
  });

  // Candidate Search & Advanced Filtering Listeners (Phase 4B - Step 5)
  if (elements.candidateSearchInput) {
    elements.candidateSearchInput.addEventListener('input', (e) => {
      state.filters.searchQuery = e.target.value;
      renderLeaderboard();
    });
  }

  if (elements.searchClearBtn) {
    elements.searchClearBtn.addEventListener('click', () => {
      state.filters.searchQuery = '';
      if (elements.candidateSearchInput) {
        elements.candidateSearchInput.value = '';
        elements.candidateSearchInput.focus();
      }
      renderLeaderboard();
    });
  }

  if (elements.toggleAdvancedFiltersBtn && elements.advancedFiltersPanel) {
    elements.toggleAdvancedFiltersBtn.addEventListener('click', () => {
      state.filters.isAdvancedOpen = !state.filters.isAdvancedOpen;
      elements.advancedFiltersPanel.style.display = state.filters.isAdvancedOpen ? 'block' : 'none';
      elements.toggleAdvancedFiltersBtn.setAttribute('aria-expanded', state.filters.isAdvancedOpen ? 'true' : 'false');
    });
  }

  if (elements.clearAllFiltersBtn) {
    elements.clearAllFiltersBtn.addEventListener('click', () => {
      clearAllFilters();
    });
  }

  if (elements.filterMinScore) {
    elements.filterMinScore.addEventListener('input', (e) => {
      state.filters.minScore = e.target.value.trim() !== '' ? Number(e.target.value) : null;
      renderLeaderboard();
    });
  }

  if (elements.filterMaxScore) {
    elements.filterMaxScore.addEventListener('input', (e) => {
      state.filters.maxScore = e.target.value.trim() !== '' ? Number(e.target.value) : null;
      renderLeaderboard();
    });
  }

  if (elements.filterMinExperience) {
    elements.filterMinExperience.addEventListener('change', (e) => {
      state.filters.minExperience = e.target.value;
      renderLeaderboard();
    });
  }

  if (elements.filterReviewStatus) {
    elements.filterReviewStatus.addEventListener('change', (e) => {
      state.filters.reviewStatus = e.target.value;
      renderLeaderboard();
    });
  }

  if (elements.filterSkillKeyword) {
    elements.filterSkillKeyword.addEventListener('input', (e) => {
      state.filters.skillKeyword = e.target.value;
      renderLeaderboard();
    });
  }

  // CSV Export Event Listeners (Phase 4B - Step 6)
  if (elements.exportCsvBtn && elements.exportDropdownMenu) {
    elements.exportCsvBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      const isHidden = elements.exportDropdownMenu.style.display === 'none';
      elements.exportDropdownMenu.style.display = isHidden ? 'block' : 'none';
      elements.exportCsvBtn.setAttribute('aria-expanded', isHidden ? 'true' : 'false');
    });

    document.addEventListener('click', (e) => {
      if (!e.target.closest('.export-dropdown-wrap') && elements.exportDropdownMenu) {
        elements.exportDropdownMenu.style.display = 'none';
        if (elements.exportCsvBtn) elements.exportCsvBtn.setAttribute('aria-expanded', 'false');
      }
    });
  }

  if (elements.exportFilteredBtn) {
    elements.exportFilteredBtn.addEventListener('click', () => {
      if (elements.exportDropdownMenu) elements.exportDropdownMenu.style.display = 'none';
      exportCurrentFilteredView();
    });
  }

  if (elements.exportAllBtn) {
    elements.exportAllBtn.addEventListener('click', () => {
      if (elements.exportDropdownMenu) elements.exportDropdownMenu.style.display = 'none';
      exportFullLeaderboard();
    });
  }

  // -------------------------------------------------------------------------
  // Bulk Upload Dropzone & Queue Event Listeners (Phase 3 Step 4)
  // -------------------------------------------------------------------------
  if (elements.resumeDropzone) {
    // Prevent default drag behaviors
    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
      elements.resumeDropzone.addEventListener(eventName, (e) => {
        e.preventDefault();
        e.stopPropagation();
      });
    });

    // Visual dragover feedback
    ['dragenter', 'dragover'].forEach(eventName => {
      elements.resumeDropzone.addEventListener(eventName, () => {
        if (!state.isUploadingBatch) {
          elements.resumeDropzone.classList.add('is-dragover');
        }
      });
    });

    ['dragleave', 'dragend'].forEach(eventName => {
      elements.resumeDropzone.addEventListener(eventName, () => {
        elements.resumeDropzone.classList.remove('is-dragover');
      });
    });

    // Drop handler
    elements.resumeDropzone.addEventListener('drop', (e) => {
      elements.resumeDropzone.classList.remove('is-dragover');
      if (state.isUploadingBatch) return;
      const files = e.dataTransfer ? e.dataTransfer.files : null;
      if (files && files.length > 0) {
        handleFilesSelected(files);
      }
    });

    // Dropzone click & keyboard trigger
    elements.resumeDropzone.addEventListener('click', (e) => {
      if (e.target && e.target.id === 'browse-files-btn') return;
      if (!state.isUploadingBatch && elements.resumeFileInput) {
        elements.resumeFileInput.click();
      }
    });

    elements.resumeDropzone.addEventListener('keydown', (e) => {
      if ((e.key === 'Enter' || e.key === ' ') && !state.isUploadingBatch && elements.resumeFileInput) {
        e.preventDefault();
        elements.resumeFileInput.click();
      }
    });
  }

  // Browse Files Button Click
  if (elements.browseFilesBtn) {
    elements.browseFilesBtn.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (!state.isUploadingBatch && elements.resumeFileInput) {
        elements.resumeFileInput.click();
      }
    });
  }

  // Hidden File Input Change
  if (elements.resumeFileInput) {
    elements.resumeFileInput.addEventListener('change', () => {
      const files = elements.resumeFileInput.files;
      if (files && files.length > 0) {
        handleFilesSelected(files);
      }
    });
  }

  // Clear Queue Button
  if (elements.clearQueueBtn) {
    elements.clearQueueBtn.addEventListener('click', () => {
      clearUploadQueue();
    });
  }

  // Upload Batch Button
  if (elements.uploadBatchBtn) {
    elements.uploadBatchBtn.addEventListener('click', () => {
      handleUploadBatch();
    });
  }

  // Outcome Dismiss Button
  if (elements.outcomeDismissBtn) {
    elements.outcomeDismissBtn.addEventListener('click', () => {
      if (elements.uploadOutcomeCard) {
        elements.uploadOutcomeCard.classList.add('is-hidden');
      }
      if (elements.resumeDropzone) {
        elements.resumeDropzone.focus();
      }
    });
  }

  // -------------------------------------------------------------------------
  // Batch History & Details Event Listeners (Phase 3 Step 5)
  // -------------------------------------------------------------------------
  if (elements.refreshBatchesBtn) {
    elements.refreshBatchesBtn.addEventListener('click', () => {
      loadBatches(true);
    });
  }

  if (elements.retryBatchesBtn) {
    elements.retryBatchesBtn.addEventListener('click', () => {
      loadBatches(true);
    });
  }

  if (elements.batchDetailBackBtn) {
    elements.batchDetailBackBtn.addEventListener('click', () => {
      showBatchHistoryList();
    });
  }

  if (elements.batchDetailRefreshBtn) {
    elements.batchDetailRefreshBtn.addEventListener('click', () => {
      refreshCurrentBatchDetail();
    });
  }

  if (elements.retryBatchDetailBtn) {
    elements.retryBatchDetailBtn.addEventListener('click', () => {
      if (state.selectedBatchId) {
        loadBatchDetail(state.selectedBatchId);
      }
    });
  }

  // -------------------------------------------------------------------------
  // Recruiter Job Authoring Event Listeners (Phase 4B Step 1)
  // -------------------------------------------------------------------------
  if (elements.openJobModalHeaderBtn) {
    elements.openJobModalHeaderBtn.addEventListener('click', () => openJobModal());
  }
  if (elements.openJobModalBatchBtn) {
    elements.openJobModalBatchBtn.addEventListener('click', () => openJobModal());
  }
  if (elements.openJobModalLeaderboardBtn) {
    elements.openJobModalLeaderboardBtn.addEventListener('click', () => openJobModal());
  }
  if (elements.jobModalCloseBtn) {
    elements.jobModalCloseBtn.addEventListener('click', () => closeJobModal());
  }
  if (elements.jobModalCancelBtn) {
    elements.jobModalCancelBtn.addEventListener('click', () => closeJobModal());
  }
  if (elements.jobModalBackdrop) {
    elements.jobModalBackdrop.addEventListener('click', (e) => {
      if (e.target === elements.jobModalBackdrop) {
        closeJobModal();
      }
    });
  }

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && state.isJobModalOpen) {
      closeJobModal();
    }
  });

  if (elements.jobModalInputDesc) {
    elements.jobModalInputDesc.addEventListener('input', () => {
      const len = elements.jobModalInputDesc.value.length;
      if (elements.jobModalCharCount) {
        elements.jobModalCharCount.textContent = len.toLocaleString();
      }
      if (elements.jobModalDescStats) {
        if (len > 45000) {
          elements.jobModalDescStats.classList.add('near-limit');
        } else {
          elements.jobModalDescStats.classList.remove('near-limit');
        }
      }
    });
  }

  if (elements.jobModalExtractBtn) {
    elements.jobModalExtractBtn.addEventListener('click', () => {
      handleExtractJobSkills();
    });
  }

  if (elements.jobModalAddSkillBtn) {
    elements.jobModalAddSkillBtn.addEventListener('click', () => {
      submitCustomSkillFromInput();
    });
  }

  if (elements.jobModalCustomSkillInput) {
    elements.jobModalCustomSkillInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        submitCustomSkillFromInput();
      }
    });
  }

  if (elements.jobModalClearSkillsBtn) {
    elements.jobModalClearSkillsBtn.addEventListener('click', () => {
      state.jobModalSkills = [];
      renderJobModalSkills();
    });
  }

  if (elements.jobModalSkillsWrap) {
    elements.jobModalSkillsWrap.addEventListener('click', (e) => {
      const removeBtn = e.target.closest('.skill-chip-remove');
      if (removeBtn && removeBtn.dataset.skill) {
        removeJobModalSkill(removeBtn.dataset.skill);
      }
    });
  }

  if (elements.jobModalForm) {
    elements.jobModalForm.addEventListener('submit', (e) => {
      e.preventDefault();
      handleSaveJob(e);
    });
  }
}

/* =============================================================================
   Bulk Resume Ingestion & Batch Processing Logic (Phase 3 Step 4)
   ============================================================================= */

/**
 * Format bytes into human-readable size string.
 */
function formatBytes(bytes) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

/**
 * Helper to compute client-side SHA-256 for local queue deduplication.
 * Falls back to metadata signature if crypto.subtle is unavailable.
 */
async function computeClientFileHash(file) {
  try {
    if (window.crypto && window.crypto.subtle) {
      const buffer = await file.arrayBuffer();
      const hashBuffer = await window.crypto.subtle.digest('SHA-256', buffer);
      const hashArray = Array.from(new Uint8Array(hashBuffer));
      return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
    }
  } catch (err) {
    console.warn('SubtleCrypto unavailable for queue deduplication; using file metadata signature.', err);
  }
  return `${file.name}_${file.size}_${file.lastModified}`;
}

/**
 * Processes selected files from dropzone or file dialog, performs client-side
 * validation, deduplicates within queue, and adds valid files to state.uploadQueue.
 *
 * @param {FileList|File[]} files
 */
async function handleFilesSelected(files) {
  if (!files || files.length === 0) return;

  const MAX_PER_BATCH = 100;
  const MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
  const MAX_BATCH_TOTAL_SIZE = 500 * 1024 * 1024; // 500 MB aggregate batch limit
  const ALLOWED_EXTS = ['pdf', 'docx', 'txt'];

  let addedCount = 0;
  let skippedLimitCount = 0;
  let skippedDuplicateCount = 0;
  let skippedInvalidExtCount = 0;
  let skippedOversizeCount = 0;

  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    if (!file) continue;

    // 1. Enforce 100 total files queue limit
    if (state.uploadQueue.length >= MAX_PER_BATCH) {
      skippedLimitCount++;
      continue;
    }

    // 2. Validate file extension
    const extParts = file.name.split('.');
    const ext = extParts.length > 1 ? extParts.pop().toLowerCase() : '';
    if (!ALLOWED_EXTS.includes(ext)) {
      skippedInvalidExtCount++;
      showToast(`"${file.name}" has an unsupported format. Only PDF, DOCX, and TXT are supported.`, 'error');
      continue;
    }

    // 3. Validate file size (10 MB max)
    if (file.size > MAX_FILE_SIZE) {
      skippedOversizeCount++;
      showToast(`"${file.name}" (${formatBytes(file.size)}) exceeds the 10 MB per-file limit.`, 'error');
      continue;
    }

    // 3b. Validate aggregate batch size limit (500 MB max)
    const currentQueueBytes = state.uploadQueue.reduce((acc, f) => acc + f.size, 0);
    if (currentQueueBytes + file.size > MAX_BATCH_TOTAL_SIZE) {
      showToast(`Adding "${file.name}" would exceed the 500 MB total batch limit.`, 'warning');
      continue;
    }

    // 4. Client-side duplicate check within queue
    const hash = await computeClientFileHash(file);
    const isDuplicate = state.uploadQueue.some(item => item.hash === hash || (item.name === file.name && item.size === file.size));
    if (isDuplicate) {
      skippedDuplicateCount++;
      showToast(`"${file.name}" is already in your upload queue (skipped duplicate selection).`, 'info');
      continue;
    }

    // Add to queue
    state.uploadQueue.push({
      file,
      id: hash,
      name: file.name,
      size: file.size,
      ext,
      hash
    });
    addedCount++;
  }

  // Reset file input so selecting the same file later triggers change event
  if (elements.resumeFileInput) {
    elements.resumeFileInput.value = '';
  }

  if (skippedLimitCount > 0) {
    showToast(`Batch capacity reached (${MAX_PER_BATCH} files maximum). ${skippedLimitCount} file(s) omitted.`, 'info');
  }

  if (addedCount > 0) {
    renderUploadQueue();
  }
}

/**
 * Re-renders the selected files queue in the UI.
 */
function renderUploadQueue() {
  const queueLength = state.uploadQueue.length;

  if (elements.queueCountBadge) {
    elements.queueCountBadge.textContent = `${queueLength} / 100 Files`;
  }

  if (queueLength === 0) {
    if (elements.queueSection) elements.queueSection.classList.add('is-hidden');
    if (elements.uploadBatchBtn) elements.uploadBatchBtn.disabled = true;
    if (elements.uploadActionInfo) {
      elements.uploadActionInfo.textContent = 'Select files to begin. Up to 100 files allowed per batch.';
    }
    if (elements.queueList) elements.queueList.innerHTML = '';
    return;
  }

  // Calculate total queued size
  const totalBytes = state.uploadQueue.reduce((acc, f) => acc + f.size, 0);

  if (elements.queueSection) elements.queueSection.classList.remove('is-hidden');
  if (elements.queueSizeBadge) elements.queueSizeBadge.textContent = `${formatBytes(totalBytes)} / 500 MB`;
  if (elements.uploadBatchBtn) {
    elements.uploadBatchBtn.disabled = state.isUploadingBatch ? true : false;
  }
  if (elements.uploadActionInfo) {
    elements.uploadActionInfo.textContent = `${queueLength} file${queueLength > 1 ? 's' : ''} queued (${formatBytes(totalBytes)}). Ready to upload.`;
  }

  if (!elements.queueList) return;

  // Build queue items list
  elements.queueList.innerHTML = '';
  state.uploadQueue.forEach((item, index) => {
    const itemEl = document.createElement('div');
    itemEl.className = 'queue-item';

    const extClass = `icon-${item.ext}`;
    const hashSnippet = item.hash ? `${item.hash.substring(0, 8)}...` : '';

    itemEl.innerHTML = `
      <div class="queue-item-left">
        <div class="queue-file-icon ${extClass}" aria-hidden="true">${escapeHtml(item.ext)}</div>
        <div class="queue-item-meta">
          <span class="queue-item-name" title="${escapeHtml(item.name)}">${escapeHtml(item.name)}</span>
          <span class="queue-item-sub">
            <span>${formatBytes(item.size)}</span>
            <span>&bull;</span>
            <span style="font-family: var(--font-mono); font-size: 0.6875rem;">SHA: ${escapeHtml(hashSnippet)}</span>
          </span>
        </div>
      </div>
      <button type="button" class="queue-item-remove" data-index="${index}" aria-label="Remove ${escapeHtml(item.name)} from queue" title="Remove file" ${state.isUploadingBatch ? 'disabled' : ''}>
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <line x1="18" y1="6" x2="6" y2="18"></line>
          <line x1="6" y1="6" x2="18" y2="18"></line>
        </svg>
      </button>
    `;

    const removeBtn = itemEl.querySelector('.queue-item-remove');
    if (removeBtn) {
      removeBtn.addEventListener('click', () => {
        removeFileFromQueue(index);
      });
    }

    elements.queueList.appendChild(itemEl);
  });
}

/**
 * Removes an individual file item from the upload queue.
 *
 * @param {number} index
 */
function removeFileFromQueue(index) {
  if (state.isUploadingBatch) return;
  if (index >= 0 && index < state.uploadQueue.length) {
    state.uploadQueue.splice(index, 1);
    renderUploadQueue();
  }
}

/**
 * Clears all files from the upload queue.
 */
function clearUploadQueue() {
  if (state.isUploadingBatch) return;
  state.uploadQueue = [];
  renderUploadQueue();
  if (elements.resumeFileInput) {
    elements.resumeFileInput.value = '';
  }
  showToast('Upload queue cleared.', 'info');
}

/**
 * Submits the queued files as a multipart batch upload to the backend service.
 */
async function handleUploadBatch() {
  if (state.isUploadingBatch) return;

  if (state.uploadQueue.length === 0) {
    showToast('Please select at least one resume file to upload.', 'error');
    return;
  }

  if (state.uploadQueue.length > 100) {
    showToast('Maximum 100 files allowed per batch.', 'error');
    return;
  }

  // Lock UI & begin upload state
  state.isUploadingBatch = true;
  state.uploadStartTime = Date.now();

  if (elements.uploadBatchBtn) {
    elements.uploadBatchBtn.disabled = true;
    elements.uploadBatchBtn.classList.add('is-disabled');
  }
  if (elements.uploadBtnIcon) elements.uploadBtnIcon.style.display = 'none';
  if (elements.uploadBtnText) {
    elements.uploadBtnText.innerHTML = '<span class="spinner" style="margin-right: 6px;"></span> Processing Batch...';
  }
  if (elements.clearQueueBtn) elements.clearQueueBtn.disabled = true;
  if (elements.batchNameInput) elements.batchNameInput.disabled = true;
  if (elements.batchJobSelect) elements.batchJobSelect.disabled = true;
  if (elements.resumeDropzone) elements.resumeDropzone.style.pointerEvents = 'none';

  // Disable all remove buttons in queue
  const removeButtons = document.querySelectorAll('.queue-item-remove');
  removeButtons.forEach(btn => { btn.disabled = true; });

  // Hide any previous outcome card
  if (elements.uploadOutcomeCard) {
    elements.uploadOutcomeCard.classList.add('is-hidden');
  }

  // Show active processing banner with elapsed timer
  if (elements.uploadProcessingBanner) {
    elements.uploadProcessingBanner.classList.remove('is-hidden');
  }
  if (elements.processingElapsedTimer) {
    elements.processingElapsedTimer.textContent = 'Elapsed: 00:00';
  }

  state.uploadTimerInterval = setInterval(() => {
    const elapsedSec = Math.floor((Date.now() - state.uploadStartTime) / 1000);
    const mins = String(Math.floor(elapsedSec / 60)).padStart(2, '0');
    const secs = String(elapsedSec % 60).padStart(2, '0');
    if (elements.processingElapsedTimer) {
      elements.processingElapsedTimer.textContent = `Elapsed: ${mins}:${secs}`;
    }
  }, 1000);

  // Build FormData with exact backend field names
  const formData = new FormData();
  for (const item of state.uploadQueue) {
    formData.append('files', item.file, item.file.name);
  }

  const batchName = elements.batchNameInput ? elements.batchNameInput.value.trim() : '';
  if (batchName) {
    formData.append('batchName', batchName);
  }

  const selectedJobId = elements.batchJobSelect ? elements.batchJobSelect.value : '';
  if (selectedJobId) {
    formData.append('jobDescriptionId', selectedJobId);
  }

  try {
    const acceptedResult = await window.api.uploadBatch(formData);

    // Safeguard 3 & UX: Clear upload queue and reset form inputs immediately
    state.uploadQueue = [];
    renderUploadQueue();
    if (elements.batchNameInput) elements.batchNameInput.value = '';
    if (elements.resumeFileInput) elements.resumeFileInput.value = '';

    const batch = acceptedResult.batch || acceptedResult;
    const batchId = batch.id;
    const totalFiles = batch.totalFiles != null ? batch.totalFiles : 1;

    showToast(`Batch #${batchId} accepted! Processing ${totalFiles} resumes in background...`, 'info', 4000);

    // Refresh batch history list immediately so the new batch appears as PROCESSING
    loadBatches(false);

    // Start background progress polling with terminal state completion
    startBatchPolling(batchId, selectedJobId, totalFiles);

  } catch (err) {
    showErrorBanner('Batch Upload Failed', err.message || 'An error occurred while uploading and staging the batch.');
    showToast(err.message || 'Batch upload failed.', 'error');
    if (state.uploadTimerInterval) {
      clearInterval(state.uploadTimerInterval);
      state.uploadTimerInterval = null;
    }
    if (elements.uploadProcessingBanner) {
      elements.uploadProcessingBanner.classList.add('is-hidden');
    }
  } finally {
    // Unlock upload form controls immediately
    state.isUploadingBatch = false;

    if (elements.clearQueueBtn) elements.clearQueueBtn.disabled = false;
    if (elements.batchNameInput) elements.batchNameInput.disabled = false;
    if (elements.batchJobSelect) elements.batchJobSelect.disabled = false;
    if (elements.resumeDropzone) elements.resumeDropzone.style.pointerEvents = '';
    if (elements.uploadBatchBtn) {
      elements.uploadBatchBtn.disabled = state.uploadQueue.length === 0;
      elements.uploadBatchBtn.classList.remove('is-disabled');
    }
    if (elements.uploadBtnIcon) elements.uploadBtnIcon.style.display = '';
    if (elements.uploadBtnText) elements.uploadBtnText.textContent = 'Upload & Process Batch';

    const activeRemoveButtons = document.querySelectorAll('.queue-item-remove');
    activeRemoveButtons.forEach(btn => { btn.disabled = false; });
  }
}

/**
 * Starts adaptive status polling for an active background batch.
 * Updates progress percentage, file counts, and automatically stops upon terminal state.
 *
 * @param {number|string} batchId
 * @param {string|number} [targetJobId]
 * @param {number} [initialTotalFiles]
 */
function startBatchPolling(batchId, targetJobId, initialTotalFiles = 1) {
  if (state.pollingInterval) {
    clearInterval(state.pollingInterval);
    state.pollingInterval = null;
  }

  state.activePollingBatchId = Number(batchId);
  state.pollingTargetJobId = targetJobId;

  if (elements.uploadProcessingBanner) {
    elements.uploadProcessingBanner.classList.remove('is-hidden');
  }
  if (elements.processingBannerTitle) {
    elements.processingBannerTitle.textContent = `Processing Batch #${batchId}...`;
  }
  if (elements.processingBannerDescription) {
    elements.processingBannerDescription.textContent =
      'Background worker is parsing documents, computing SHA-256 hashes, extracting IT skills, and evaluating candidates.';
  }
  if (elements.processingProgressBar) {
    elements.processingProgressBar.style.width = '0%';
  }
  if (elements.processingProgressText) {
    elements.processingProgressText.textContent = `0 of ${initialTotalFiles} files processed (0%)`;
  }
  if (elements.processingProgressStatus) {
    elements.processingProgressStatus.className = 'badge badge-info';
    elements.processingProgressStatus.textContent = 'PROCESSING';
  }

  const pollStartTime = Date.now();
  const MAX_POLL_DURATION_MS = 600000; // 10 minutes safety timeout

  state.pollingInterval = setInterval(async () => {
    if (Date.now() - pollStartTime > MAX_POLL_DURATION_MS) {
      stopBatchPolling();
      showToast(`Batch #${batchId} processing timed out. Please check Batch History.`, 'warning', 6000);
      loadBatches(false);
      return;
    }

    try {
      const statusDto = await window.api.getBatchStatus(batchId);
      if (!statusDto) return;

      const totalFiles = statusDto.totalFiles != null ? statusDto.totalFiles : initialTotalFiles;
      const processedFiles = statusDto.processedFiles != null ? statusDto.processedFiles : 0;
      const status = statusDto.status || 'PROCESSING';
      const pct = Math.min(100, Math.round((processedFiles / Math.max(1, totalFiles)) * 100));

      if (elements.processingProgressBar) {
        elements.processingProgressBar.style.width = `${pct}%`;
      }
      if (elements.processingProgressText) {
        elements.processingProgressText.textContent = `${processedFiles} of ${totalFiles} files processed (${pct}%)`;
      }
      if (elements.processingProgressStatus) {
        elements.processingProgressStatus.textContent = status;
        if (status === 'COMPLETED') elements.processingProgressStatus.className = 'badge badge-success';
        else if (status === 'PARTIAL_SUCCESS') elements.processingProgressStatus.className = 'badge badge-warning';
        else if (status === 'FAILED') elements.processingProgressStatus.className = 'badge badge-danger';
        else elements.processingProgressStatus.className = 'badge badge-info';
      }

      // If user is currently inspecting this batch's detail view in Batch History, refresh it live
      if (state.selectedBatchId === Number(batchId) && elements.batchDetailView && !elements.batchDetailView.classList.contains('is-hidden')) {
        loadBatchDetail(batchId);
      }

      // Stop condition: Terminal state reached
      if (status === 'COMPLETED' || status === 'PARTIAL_SUCCESS' || status === 'FAILED') {
        stopBatchPolling();

        // Fetch full batch details to render completed outcomes card
        try {
          const detail = await window.api.getBatchById(batchId);
          renderBatchOutcome(detail, targetJobId);
        } catch (detailErr) {
          console.warn('Could not fetch final batch detail:', detailErr);
        }

        loadBatches(false);

        if (status === 'COMPLETED') {
          showToast(`Batch #${batchId} processed successfully!`, 'success', 4000);
        } else if (status === 'PARTIAL_SUCCESS') {
          showToast(`Batch #${batchId} finished with partial results.`, 'warning', 5000);
        } else {
          showToast(`Batch #${batchId} failed.`, 'error', 5000);
        }
      }

    } catch (pollErr) {
      console.warn(`Polling status error for Batch #${batchId}:`, pollErr);
    }
  }, 1500);
}

/**
 * Stops any active background batch polling and cleans up timers.
 */
function stopBatchPolling() {
  if (state.pollingInterval) {
    clearInterval(state.pollingInterval);
    state.pollingInterval = null;
  }
  state.activePollingBatchId = null;
  state.pollingTargetJobId = null;

  if (state.uploadTimerInterval) {
    clearInterval(state.uploadTimerInterval);
    state.uploadTimerInterval = null;
  }

  if (elements.uploadProcessingBanner) {
    elements.uploadProcessingBanner.classList.add('is-hidden');
  }
}

/**
 * Presents the completed batch results and individual file outcomes table.
 *
 * @param {Object} detail - ResumeBatchDetailResponseDto
 * @param {string|number} [targetJobId] - Optional job ID screened against
 */
function renderBatchOutcome(detail, targetJobId) {
  if (!detail || !elements.uploadOutcomeCard) return;

  const batch = detail.batch || detail;
  const files = Array.isArray(detail.files) ? detail.files : [];

  const totalCount = batch.totalFiles != null ? batch.totalFiles : files.length;
  const parsedCount = files.filter(f => f.status === 'PARSED').length;
  const duplicateCount = files.filter(f => f.status === 'DUPLICATE').length;
  const failedCount = files.filter(f => f.status === 'FAILED').length;

  if (elements.outcomeBatchTitle) {
    const namePart = batch.batchName ? ` &bull; ${escapeHtml(batch.batchName)}` : '';
    elements.outcomeBatchTitle.innerHTML = `Batch #${batch.id || ''}${namePart}`;
  }

  if (elements.outcomeBatchSubtitle) {
    const timeStr = new Date().toLocaleTimeString();
    const jobName = (batch && batch.jobTitle) ? ` &bull; Target Job: <strong>${escapeHtml(batch.jobTitle)}</strong>` : '';
    elements.outcomeBatchSubtitle.innerHTML = `Completed at ${timeStr} &bull; Synchronous processing executed across ${totalCount} file(s).${jobName}`;
  }

  if (elements.outcomeTotalCount) elements.outcomeTotalCount.textContent = String(totalCount);
  if (elements.outcomeParsedCount) elements.outcomeParsedCount.textContent = String(parsedCount);
  if (elements.outcomeDuplicateCount) elements.outcomeDuplicateCount.textContent = String(duplicateCount);
  if (elements.outcomeFailedCount) elements.outcomeFailedCount.textContent = String(failedCount);

  // Configure header actions
  if (elements.outcomeHeaderActions) {
    elements.outcomeHeaderActions.innerHTML = '';

    const effectiveJobId = targetJobId || (batch && (batch.jobDescriptionId || batch.jobId));
    if (effectiveJobId) {
      const viewLeaderboardBtn = document.createElement('button');
      viewLeaderboardBtn.type = 'button';
      viewLeaderboardBtn.className = 'btn btn-sm btn-primary';
      viewLeaderboardBtn.id = 'outcome-view-leaderboard-btn';
      viewLeaderboardBtn.innerHTML = `
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <circle cx="12" cy="8" r="7"></circle>
          <polyline points="8.21 13.89 7 23 12 20 17 23 15.79 13.88"></polyline>
        </svg>
        <span>View Screened Candidates on Leaderboard</span>
      `;

      viewLeaderboardBtn.addEventListener('click', () => {
        switchWorkspace('leaderboard');
        if (elements.jobSelect) {
          elements.jobSelect.value = String(effectiveJobId);
          handleJobSelectionChange();
        }
      });

      elements.outcomeHeaderActions.appendChild(viewLeaderboardBtn);
    }
  }

  // Populate individual file outcomes table
  if (elements.outcomeFilesTbody) {
    elements.outcomeFilesTbody.innerHTML = '';

    if (files.length === 0) {
      elements.outcomeFilesTbody.innerHTML = `
        <tr>
          <td colspan="4" style="text-align: center; color: var(--color-text-muted); padding: var(--space-4);">
            No individual file outcomes recorded for this batch.
          </td>
        </tr>
      `;
    } else {
      files.forEach(file => {
        const tr = document.createElement('tr');

        let statusBadge = '';
        if (file.status === 'PARSED') {
          statusBadge = '<span class="badge badge-selected">PARSED</span>';
        } else if (file.status === 'DUPLICATE') {
          statusBadge = '<span class="badge badge-reviewed">DUPLICATE</span>';
        } else {
          statusBadge = '<span class="badge badge-rejected">FAILED</span>';
        }

        const hash = file.fileHash || '';
        const hashDisplay = hash.length >= 16
          ? `${hash.substring(0, 8)}...${hash.substring(hash.length - 8)}`
          : (hash || 'N/A');

        let detailNote = '';
        if (file.status === 'PARSED') {
          detailNote = '<span style="color: var(--color-selected-dark); font-weight: var(--font-medium);">Candidate &amp; skills parsed successfully</span>';
        } else if (file.status === 'DUPLICATE') {
          detailNote = `<span style="color: var(--color-override-dark);">${escapeHtml(file.errorMessage || 'Content duplicate (file hash already exists in database)')}</span>`;
        } else {
          detailNote = `<span style="color: var(--color-rejected-dark); font-weight: var(--font-medium);">${escapeHtml(file.errorMessage || 'Processing failed')}</span>`;
        }

        tr.innerHTML = `
          <td>
            <strong style="color: var(--color-text-main); word-break: break-word;">${escapeHtml(file.fileName || 'Untitled File')}</strong>
          </td>
          <td>${statusBadge}</td>
          <td>
            <code class="outcome-hash-code" title="${escapeHtml(hash)}">${escapeHtml(hashDisplay)}</code>
          </td>
          <td>${detailNote}</td>
        `;

        elements.outcomeFilesTbody.appendChild(tr);
      });
    }
  }

  // Show outcome card and scroll into view smoothly
  elements.uploadOutcomeCard.classList.remove('is-hidden');
  elements.uploadOutcomeCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

/* =============================================================================
   Batch Ingestion History & Outcomes View Logic (Phase 3 Step 5)
   ============================================================================= */

/**
 * Formats an ISO datetime string into human-readable locale format.
 *
 * @param {string} isoString
 * @returns {string}
 */
function formatDateTime(isoString) {
  if (!isoString) return 'N/A';
  try {
    const d = new Date(isoString);
    if (isNaN(d.getTime())) return String(isoString);
    return d.toLocaleString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  } catch (e) {
    return String(isoString);
  }
}

/**
 * Returns HTML badge markup corresponding to batch execution status.
 *
 * @param {string} status - COMPLETED | PROCESSING | FAILED | PENDING
 * @returns {string} HTML markup
 */
function getBatchStatusBadge(status) {
  const norm = String(status || '').toUpperCase();
  if (norm === 'COMPLETED') {
    return '<span class="badge badge-selected">COMPLETED</span>';
  } else if (norm === 'FAILED') {
    return '<span class="badge badge-rejected">FAILED</span>';
  } else if (norm === 'PROCESSING' || norm === 'IN_PROGRESS') {
    return '<span class="badge badge-shortlisted">PROCESSING</span>';
  } else if (norm === 'PENDING') {
    return '<span class="badge badge-reviewed">PENDING</span>';
  }
  return `<span class="badge" style="background-color: var(--color-bg-alt); border-color: var(--color-border); color: var(--color-text-body);">${escapeHtml(norm || 'UNKNOWN')}</span>`;
}

/**
 * Returns HTML badge markup for individual file parsing outcomes.
 *
 * @param {string} status - PARSED | DUPLICATE | FAILED
 * @returns {string} HTML markup
 */
function getFileOutcomeBadge(status) {
  const norm = String(status || '').toUpperCase();
  if (norm === 'PARSED') {
    return '<span class="badge badge-selected">PARSED</span>';
  } else if (norm === 'DUPLICATE') {
    return '<span class="badge badge-reviewed">DUPLICATE</span>';
  } else if (norm === 'FAILED') {
    return '<span class="badge badge-rejected">FAILED</span>';
  }
  return `<span class="badge" style="background-color: var(--color-bg-alt); border-color: var(--color-border); color: var(--color-text-body);">${escapeHtml(norm || 'PENDING')}</span>`;
}

/**
 * Copies the full SHA-256 hash string to clipboard with feedback.
 *
 * @param {string} hash
 * @param {HTMLElement} btnElement
 */
async function copyHashToClipboard(hash, btnElement) {
  if (!hash) return;
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(hash);
    } else {
      const ta = document.createElement('textarea');
      ta.value = hash;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }

    if (btnElement) {
      btnElement.classList.add('copied');
      const originalText = btnElement.getAttribute('data-original-text') || btnElement.innerHTML;
      btnElement.setAttribute('data-original-text', originalText);
      btnElement.innerHTML = `
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <polyline points="20 6 9 17 4 12"></polyline>
        </svg>
        <span>Copied!</span>
      `;
      setTimeout(() => {
        btnElement.classList.remove('copied');
        btnElement.innerHTML = originalText;
      }, 2000);
    }
    showToast('Full SHA-256 hash copied to clipboard.', 'info', 2000);
  } catch (err) {
    showToast('Could not copy hash to clipboard.', 'error');
  }
}

/**
 * Loads all resume batches from GET /api/v1/resumes/batches and renders history table.
 *
 * @param {boolean} [showFeedback=false] - Whether to show a success toast on refresh
 */
async function loadBatches(showFeedback = false) {
  if (state.isLoadingBatches) return;
  state.isLoadingBatches = true;

  if (elements.batchHistoryLoading) elements.batchHistoryLoading.classList.remove('is-hidden');
  if (elements.batchHistoryError) elements.batchHistoryError.classList.add('is-hidden');
  if (elements.batchHistoryEmpty) elements.batchHistoryEmpty.classList.add('is-hidden');
  if (elements.batchHistoryTableContainer) elements.batchHistoryTableContainer.classList.add('is-hidden');

  if (elements.refreshBatchesBtn) elements.refreshBatchesBtn.disabled = true;
  if (elements.refreshBatchesIcon) elements.refreshBatchesIcon.classList.add('spinner');

  try {
    const batches = await window.api.getBatches();
    state.batches = Array.isArray(batches) ? batches : [];

    if (elements.batchHistoryLoading) elements.batchHistoryLoading.classList.add('is-hidden');

    if (state.batches.length === 0) {
      if (elements.batchHistoryEmpty) elements.batchHistoryEmpty.classList.remove('is-hidden');
    } else {
      if (elements.batchHistoryTableContainer) elements.batchHistoryTableContainer.classList.remove('is-hidden');
      renderBatchesList(state.batches);
    }

    if (showFeedback) {
      showToast('Batch history refreshed.', 'info', 2000);
    }
  } catch (err) {
    if (elements.batchHistoryLoading) elements.batchHistoryLoading.classList.add('is-hidden');
    if (elements.batchHistoryErrorMessage) {
      elements.batchHistoryErrorMessage.textContent = err.message || 'Could not load batch history from backend.';
    }
    if (elements.batchHistoryError) elements.batchHistoryError.classList.remove('is-hidden');
    if (showFeedback) {
      showToast(err.message || 'Failed to refresh batch history.', 'error');
    }
  } finally {
    state.isLoadingBatches = false;
    if (elements.refreshBatchesBtn) elements.refreshBatchesBtn.disabled = false;
    if (elements.refreshBatchesIcon) elements.refreshBatchesIcon.classList.remove('spinner');
  }
}

/**
 * Renders the batch history rows in the table.
 *
 * @param {Array<Object>} batches - List of ResumeBatchResponseDto objects
 */
function renderBatchesList(batches) {
  if (!elements.batchHistoryTbody) return;
  elements.batchHistoryTbody.innerHTML = '';

  batches.forEach(b => {
    const tr = document.createElement('tr');
    tr.id = `batch-row-${b.id}`;

    const batchNameDisplay = b.batchName
      ? escapeHtml(b.batchName)
      : '<span style="color: var(--color-text-muted); font-style: italic;">Untitled Batch</span>';
    const targetJobDisplay = b.jobTitle
      ? `<span class="badge badge-info" style="font-size: var(--text-xs); font-weight: var(--font-medium); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 180px; display: inline-block; vertical-align: middle;" title="${escapeHtml(b.jobTitle)}">${escapeHtml(b.jobTitle)}</span>`
      : `<span style="color: var(--color-text-muted); font-size: var(--text-xs); font-style: italic;">No Target Job</span>`;
    const submittedStr = formatDateTime(b.createdAt);
    const totalFiles = b.totalFiles != null ? b.totalFiles : 0;
    const processedFiles = b.processedFiles != null ? b.processedFiles : 0;
    const statusBadge = getBatchStatusBadge(b.status);

    tr.innerHTML = `
      <td>
        <span class="batch-id-pill">#${escapeHtml(String(b.id))}</span>
      </td>
      <td>
        <strong style="color: var(--color-text-main);">${batchNameDisplay}</strong>
      </td>
      <td>
        ${targetJobDisplay}
      </td>
      <td style="color: var(--color-text-muted); font-size: var(--text-xs);">
        ${escapeHtml(submittedStr)}
      </td>
      <td>
        <span style="font-weight: var(--font-semibold); color: var(--color-text-main);">${processedFiles}</span>
        <span style="color: var(--color-text-muted); font-size: var(--text-xs);">/ ${totalFiles}</span>
      </td>
      <td>
        ${statusBadge}
      </td>
      <td style="text-align: right;">
        <button type="button" class="btn btn-sm btn-secondary view-batch-btn" data-batch-id="${b.id}" aria-label="View details for Batch #${b.id}">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
            <circle cx="12" cy="12" r="3"></circle>
          </svg>
          <span>View Details</span>
        </button>
      </td>
    `;

    const viewBtn = tr.querySelector('.view-batch-btn');
    if (viewBtn) {
      viewBtn.addEventListener('click', () => {
        showBatchDetail(b.id);
      });
    }

    elements.batchHistoryTbody.appendChild(tr);
  });
}

/**
 * Navigates back from Batch Details to the Batches History List view.
 */
function showBatchHistoryList() {
  state.selectedBatchId = null;
  state.selectedBatchDetail = null;

  if (elements.batchDetailView) elements.batchDetailView.classList.add('is-hidden');
  if (elements.batchHistoryListView) elements.batchHistoryListView.classList.remove('is-hidden');

  loadBatches(false);
}

/**
 * Transitions the view to the selected batch's detail view and fetches data.
 *
 * @param {number|string} batchId
 */
function showBatchDetail(batchId) {
  if (!batchId) return;

  state.selectedBatchId = batchId;

  if (elements.batchHistoryListView) elements.batchHistoryListView.classList.add('is-hidden');
  if (elements.batchDetailView) elements.batchDetailView.classList.remove('is-hidden');

  loadBatchDetail(batchId);
}

/**
 * Fetches batch detail via GET /api/v1/resumes/batches/{id} and renders outcomes.
 *
 * @param {number|string} batchId
 */
async function loadBatchDetail(batchId) {
  if (!batchId) return;
  state.isLoadingBatchDetail = true;

  if (elements.batchDetailLoading) elements.batchDetailLoading.classList.remove('is-hidden');
  if (elements.batchDetailError) elements.batchDetailError.classList.add('is-hidden');
  if (elements.batchDetailContent) elements.batchDetailContent.classList.add('is-hidden');

  if (elements.batchDetailRefreshBtn) elements.batchDetailRefreshBtn.disabled = true;

  try {
    const detail = await window.api.getBatchById(batchId);
    state.selectedBatchDetail = detail;

    if (elements.batchDetailLoading) elements.batchDetailLoading.classList.add('is-hidden');
    if (elements.batchDetailContent) elements.batchDetailContent.classList.remove('is-hidden');

    renderBatchDetail(detail);

  } catch (err) {
    if (elements.batchDetailLoading) elements.batchDetailLoading.classList.add('is-hidden');
    if (elements.batchDetailErrorMessage) {
      elements.batchDetailErrorMessage.textContent = err.message || `Could not retrieve details for Batch #${batchId}.`;
    }
    if (elements.batchDetailError) elements.batchDetailError.classList.remove('is-hidden');
    showToast(err.message || 'Failed to load batch details.', 'error');
  } finally {
    state.isLoadingBatchDetail = false;
    if (elements.batchDetailRefreshBtn) elements.batchDetailRefreshBtn.disabled = false;
  }
}

/**
 * Renders batch details header, metrics, and individual file outcomes table.
 *
 * @param {Object} detail - ResumeBatchDetailResponseDto
 */
function renderBatchDetail(detail) {
  if (!detail) return;

  const batch = detail.batch || detail;
  const files = Array.isArray(detail.files) ? detail.files : [];

  const totalFiles = batch.totalFiles != null ? batch.totalFiles : files.length;
  const parsedCount = files.filter(f => f.status === 'PARSED').length;
  const duplicateCount = files.filter(f => f.status === 'DUPLICATE').length;
  const failedCount = files.filter(f => f.status === 'FAILED').length;

  // Header Title and Subtitle
  if (elements.batchDetailTitle) {
    const namePart = batch.batchName ? ` &bull; ${escapeHtml(batch.batchName)}` : '';
    elements.batchDetailTitle.innerHTML = `Batch #${batch.id || ''}${namePart}`;
  }

  const associatedJobId = (batch && (batch.jobDescriptionId || batch.jobId)) ||
                          (detail && (detail.jobDescriptionId || detail.jobId));
  const associatedJobTitle = (batch && batch.jobTitle) || (detail && detail.jobTitle);

  if (elements.batchDetailSubtitle) {
    const dateStr = formatDateTime(batch.createdAt);
    const statusBadge = getBatchStatusBadge(batch.status);
    const jobBadge = associatedJobTitle
      ? `<span class="badge badge-info" style="font-weight: var(--font-medium); margin-left: var(--space-2);"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: -1px; margin-right: 3px;"><rect x="2" y="7" width="20" height="14" rx="2" ry="2"></rect><path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"></path></svg>${escapeHtml(associatedJobTitle)}</span>`
      : `<span class="badge badge-neutral" style="color: var(--color-text-muted); margin-left: var(--space-2);">No Target Job (General Ingestion)</span>`;

    elements.batchDetailSubtitle.innerHTML = `Created: <strong>${escapeHtml(dateStr)}</strong> &bull; Status: ${statusBadge} &bull; Target Job: ${jobBadge}`;
  }

  // Batch History Integration (Step 6B):
  // If the actual batch response contains a valid associated job ID, provide a shortcut to the corresponding leaderboard.
  // Do not invent or infer a job association from browser memory for historical batches.
  const existingJobShortcutBtn = document.getElementById('batch-detail-leaderboard-btn');
  if (existingJobShortcutBtn) {
    existingJobShortcutBtn.remove();
  }

  if (associatedJobId && elements.batchDetailActions) {
    const leaderboardShortcutBtn = document.createElement('button');
    leaderboardShortcutBtn.type = 'button';
    leaderboardShortcutBtn.className = 'btn btn-primary btn-sm';
    leaderboardShortcutBtn.id = 'batch-detail-leaderboard-btn';
    leaderboardShortcutBtn.innerHTML = `
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <line x1="18" y1="20" x2="18" y2="10"></line>
        <line x1="12" y1="20" x2="12" y2="4"></line>
        <line x1="6" y1="20" x2="6" y2="14"></line>
      </svg>
      <span>View on Leaderboard</span>
    `;
    leaderboardShortcutBtn.addEventListener('click', () => {
      switchWorkspace('leaderboard');
      if (elements.jobSelect) {
        elements.jobSelect.value = String(associatedJobId);
        handleJobSelectionChange();
      }
    });
    if (elements.batchDetailRefreshBtn) {
      elements.batchDetailActions.insertBefore(leaderboardShortcutBtn, elements.batchDetailRefreshBtn);
    } else {
      elements.batchDetailActions.appendChild(leaderboardShortcutBtn);
    }
  }

  // Metrics KPI counters
  if (elements.detailTotalCount) elements.detailTotalCount.textContent = String(totalFiles);
  if (elements.detailParsedCount) elements.detailParsedCount.textContent = String(parsedCount);
  if (elements.detailDuplicateCount) elements.detailDuplicateCount.textContent = String(duplicateCount);
  if (elements.detailFailedCount) elements.detailFailedCount.textContent = String(failedCount);

  // Populate individual file records table
  if (elements.batchDetailFilesTbody) {
    elements.batchDetailFilesTbody.innerHTML = '';

    if (files.length === 0) {
      elements.batchDetailFilesTbody.innerHTML = `
        <tr>
          <td colspan="5" style="text-align: center; color: var(--color-text-muted); padding: var(--space-6);">
            No individual file records found in this batch.
          </td>
        </tr>
      `;
    } else {
      files.forEach(file => {
        const tr = document.createElement('tr');

        const statusBadge = getFileOutcomeBadge(file.status);
        const hash = file.fileHash || '';
        const hashDisplay = hash.length >= 16
          ? `${hash.substring(0, 8)}...${hash.substring(hash.length - 8)}`
          : (hash || 'N/A');

        let noteText = '';
        if (file.status === 'PARSED') {
          noteText = '<span style="color: var(--color-selected-dark); font-weight: var(--font-medium);">Candidate &amp; skills extracted</span>';
        } else if (file.status === 'DUPLICATE') {
          noteText = `<span style="color: var(--color-override-dark);">${escapeHtml(file.errorMessage || 'Content duplicate (file hash already exists in database)')}</span>`;
        } else {
          noteText = `<span style="color: var(--color-rejected-dark); font-weight: var(--font-medium);">${escapeHtml(file.errorMessage || 'Processing failed')}</span>`;
        }

        const fileDateStr = formatDateTime(file.createdAt);

        tr.innerHTML = `
          <td>
            <strong style="color: var(--color-text-main); word-break: break-word;">${escapeHtml(file.fileName || 'Untitled File')}</strong>
          </td>
          <td>
            ${statusBadge}
          </td>
          <td>
            <div style="display: inline-flex; align-items: center;">
              <code class="outcome-hash-code" title="${escapeHtml(hash)}">${escapeHtml(hashDisplay)}</code>
              ${hash ? `
                <button type="button" class="copy-hash-btn" data-hash="${escapeHtml(hash)}" title="Copy full SHA-256 hash" aria-label="Copy hash for ${escapeHtml(file.fileName)}">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                  </svg>
                  <span>Copy</span>
                </button>
              ` : ''}
            </div>
          </td>
          <td>
            ${noteText}
          </td>
          <td style="color: var(--color-text-muted); font-size: var(--text-xs);">
            ${escapeHtml(fileDateStr)}
          </td>
        `;

        const copyBtn = tr.querySelector('.copy-hash-btn');
        if (copyBtn) {
          copyBtn.addEventListener('click', () => {
            copyHashToClipboard(hash, copyBtn);
          });
        }

        elements.batchDetailFilesTbody.appendChild(tr);
      });
    }
  }
}

/**
 * Refreshes current selected batch status and reloads its details.
 */
async function refreshCurrentBatchDetail() {
  if (!state.selectedBatchId) return;

  if (elements.batchDetailRefreshBtn) elements.batchDetailRefreshBtn.disabled = true;

  try {
    const statusDto = await window.api.getBatchStatus(state.selectedBatchId);
    await loadBatchDetail(state.selectedBatchId);
    showToast(`Batch #${state.selectedBatchId} status refreshed: ${statusDto.status || 'OK'}.`, 'info', 2000);
  } catch (err) {
    showToast(err.message || 'Could not refresh batch status.', 'error');
  } finally {
    if (elements.batchDetailRefreshBtn) elements.batchDetailRefreshBtn.disabled = false;
  }
}

/**
 * 2. Backend and Python AI Service Health Verification
 */
async function checkBackendHealth() {
  try {
    const backendHealth = await window.api.checkHealth();
    let isAiHealthy = false;

    try {
      const aiHealth = await window.api.checkAiHealth();
      isAiHealthy = aiHealth && aiHealth.aiServiceStatus === 'UP';
    } catch (aiErr) {
      isAiHealthy = false;
    }

    if (backendHealth && backendHealth.status === 'UP') {
      if (elements.backendStatusDot) {
        elements.backendStatusDot.style.backgroundColor = isAiHealthy
          ? 'var(--color-selected)'
          : 'var(--color-override)';
      }
      if (elements.backendStatusText) {
        elements.backendStatusText.textContent = isAiHealthy
          ? 'Backend & AI Connected'
          : 'Backend UP (AI Service Offline)';
      }
      if (elements.backendStatusBadge) {
        elements.backendStatusBadge.title = isAiHealthy
          ? 'Java Backend (Port 8080) and Python AI Service (Port 8000) are healthy.'
          : 'Java Backend is healthy, but Python AI Service is not responding.';
      }
    }
  } catch (err) {
    if (elements.backendStatusDot) {
      elements.backendStatusDot.style.backgroundColor = 'var(--color-rejected)';
    }
    if (elements.backendStatusText) {
      elements.backendStatusText.textContent = 'Backend Disconnected';
    }
    if (elements.backendStatusBadge) {
      elements.backendStatusBadge.title = err.message || 'Cannot reach Spring Boot service at http://localhost:8080';
    }
  }
}

/**
 * 3. Fetch Job Postings and Populate Select Dropdowns
 *
 * @param {number|string|null} [preferredSelectJobId=null] - Optional job ID to automatically select after reload
 */
async function loadJobs(preferredSelectJobId = null) {
  if (elements.jobCountHint) {
    elements.jobCountHint.textContent = 'Loading positions...';
  }

  try {
    const jobs = await window.api.getJobs();
    state.jobs = Array.isArray(jobs) ? jobs : [];

    if (elements.jobSelect) {
      // Clear options keeping initial placeholder
      elements.jobSelect.innerHTML = '<option value="" disabled selected>-- Select a Job Description --</option>';

      state.jobs.forEach(job => {
        const option = document.createElement('option');
        option.value = job.id;
        const dept = job.department ? ` (${job.department})` : '';
        const status = job.status ? ` - ${job.status}` : '';
        option.textContent = `${job.title}${dept}${status}`;
        elements.jobSelect.appendChild(option);
      });

      if (preferredSelectJobId) {
        elements.jobSelect.value = String(preferredSelectJobId);
      }
    }

    if (elements.batchJobSelect) {
      elements.batchJobSelect.innerHTML = '<option value="">-- No Target (Extract &amp; Store Only) --</option>';
      state.jobs.forEach(job => {
        const option = document.createElement('option');
        option.value = job.id;
        const dept = job.department ? ` (${job.department})` : '';
        option.textContent = `${job.title}${dept}`;
        elements.batchJobSelect.appendChild(option);
      });

      if (preferredSelectJobId) {
        elements.batchJobSelect.value = String(preferredSelectJobId);
      }
    }

    if (elements.jobCountHint) {
      elements.jobCountHint.textContent = `${state.jobs.length} position(s) available`;
    }
  } catch (err) {
    if (elements.jobCountHint) {
      elements.jobCountHint.textContent = 'Error loading positions';
    }
    showErrorBanner('Failed to Load Job Postings', err.message || 'Could not retrieve job descriptions from backend.');
  }
}

/**
 * Triggered when recruiter selects a different job from the dropdown.
 * Automatically checks if rankings already exist for this job and renders them.
 */
async function handleJobSelectionChange() {
  const selectedId = elements.jobSelect.value;
  if (!selectedId) return;

  state.selectedJobId = selectedId;
  hideErrorBanner();

  try {
    const existingLeaderboard = await window.api.getRankingsByJob(selectedId);
    if (Array.isArray(existingLeaderboard) && existingLeaderboard.length > 0) {
      state.rankings = existingLeaderboard;
      updateKpis();
      renderLeaderboard();
      showToast(`Loaded ${existingLeaderboard.length} existing candidate rankings for this job.`, 'info');
    } else {
      state.rankings = [];
      updateKpis();
      showInitialEmptyState(
        'No Candidate Rankings Generated',
        'Click "Generate Rankings" to screen applicants against this job, apply automated Top-N quota selection, and view explainable decision reasons.'
      );
    }
  } catch (err) {
    // If fetching existing rankings fails, show initial state for fresh generation
    state.rankings = [];
    updateKpis();
    showInitialEmptyState(
      'Ready to Generate Rankings',
      'Click "Generate Rankings" to screen applicants, apply Top-N selection quota, and generate AI explanations.'
    );
  }
}

/**
 * 4 & 5. Handle Generate Rankings Execution
 */
async function handleGenerateRankings() {
  if (state.isGenerating) return;

  hideErrorBanner();

  // Read Inputs
  const jobId = elements.jobSelect ? elements.jobSelect.value : '';
  const topN = elements.topNInput ? parseInt(elements.topNInput.value, 10) : 10;
  const minScore = elements.minScoreInput ? parseFloat(elements.minScoreInput.value) : 20.0;

  // Validation
  if (!jobId) {
    showErrorBanner('Target Job Required', 'Please select a job description from the dropdown before generating rankings.');
    showToast('Please select a target job description.', 'error');
    if (elements.jobSelect) elements.jobSelect.focus();
    return;
  }

  if (isNaN(topN) || topN <= 0) {
    showErrorBanner('Invalid Top-N Quota', 'Candidate selection count (Top N) must be a positive integer greater than zero.');
    showToast('Selection count (Top N) must be at least 1.', 'error');
    if (elements.topNInput) elements.topNInput.focus();
    return;
  }

  if (isNaN(minScore) || minScore < 0 || minScore > 100) {
    showErrorBanner('Invalid Score Threshold', 'Minimum match score must be a percentage between 0% and 100%.');
    showToast('Minimum score must be between 0 and 100.', 'error');
    if (elements.minScoreInput) elements.minScoreInput.focus();
    return;
  }

  // 6. Set Loading State & Prevent Duplicate Submissions
  setGeneratingLoadingState(true);

  try {
    const rankings = await window.api.generateRankings(jobId, topN, minScore);
    state.rankings = Array.isArray(rankings) ? rankings : [];

    updateKpis();

    if (state.rankings.length === 0) {
      // Determine specific empty state cause based on available information
      const hasBatches = Array.isArray(state.batches) && state.batches.length > 0;

      if (!hasBatches) {
        showInitialEmptyState(
          'No Resumes Uploaded Yet',
          'No resume files have been uploaded to the system yet. Please upload candidate resumes in the Bulk Upload workspace before generating candidate rankings.',
          'upload'
        );
        showToast('No resumes found in the system. Upload candidate resumes first.', 'info');
      } else {
        showInitialEmptyState(
          'No Eligible Screened Candidates',
          'Resume batches have been uploaded, but no eligible candidate resumes could be screened for this job posting. Ensure uploaded resumes parsed successfully or try uploading a fresh batch for this position.',
          'upload'
        );
        showToast('No eligible candidate resumes found for this job position.', 'info');
      }
    } else {
      renderLeaderboard();
      const selectedTotal = state.rankings.filter(r => r.reviewStatus === 'SELECTED').length;
      const totalCount = state.rankings.length;

      if (selectedTotal === 0) {
        showToast(
          `Ranked ${totalCount} candidate(s), but none met the minimum match threshold of ${minScore}%.`,
          'info',
          5000
        );
      } else {
        showToast(
          `Successfully ranked ${totalCount} candidate(s) (${selectedTotal} selected).`,
          'success'
        );
      }
    }
  } catch (err) {
    let errorTitle = 'Ranking Generation Failed';
    let errorMsg = err.message || 'An unexpected error occurred while evaluating candidates and generating rankings.';

    if (err.isNetworkError) {
      errorTitle = 'Backend Server Unreachable';
      errorMsg = 'Cannot connect to the Spring Boot server at http://localhost:8080. Please ensure the backend is running and accessible.';
    } else if (err.status === 404) {
      errorTitle = 'Job Position Not Found';
      errorMsg = `Job position #${jobId} was not found on the server. Please refresh the job list.`;
    } else if (err.status === 400) {
      errorTitle = 'Invalid Ranking Request';
    } else if (err.status === 500 && (errorMsg.toLowerCase().includes('ai') || errorMsg.toLowerCase().includes('microservice') || errorMsg.toLowerCase().includes('connection refused'))) {
      errorTitle = 'Python AI Service Unavailable';
      errorMsg = 'The Python NLP/matching service on port 8000 is unreachable or returned an error during candidate evaluation. Please verify that the AI microservice is running.';
    }

    showErrorBanner(errorTitle, errorMsg);
    showToast(errorMsg, 'error');

    // Restore empty state if no rankings were previously loaded
    if (state.rankings.length === 0) {
      showInitialEmptyState(
        'Ranking Generation Incomplete',
        'Could not generate candidate rankings due to an error. Review the notification details above and try again.'
      );
    }
  } finally {
    setGeneratingLoadingState(false);
  }
}

/**
 * Toggles UI interactive states during ranking generation.
 */
function setGeneratingLoadingState(isLoading) {
  state.isGenerating = isLoading;

  if (elements.generateBtn) {
    elements.generateBtn.disabled = isLoading;
  }
  if (elements.jobSelect) {
    elements.jobSelect.disabled = isLoading;
  }
  if (elements.topNInput) {
    elements.topNInput.disabled = isLoading;
  }
  if (elements.minScoreInput) {
    elements.minScoreInput.disabled = isLoading;
  }

  if (elements.generateBtnText) {
    elements.generateBtnText.textContent = isLoading ? 'Evaluating & Ranking...' : 'Generate Rankings';
  }

  if (elements.loadingState) {
    elements.loadingState.style.display = isLoading ? 'flex' : 'none';
  }

  if (elements.loadingTitle) {
    elements.loadingTitle.textContent = 'Evaluating Candidates & Generating Leaderboard';
  }
  if (elements.loadingMessage) {
    elements.loadingMessage.textContent = 'Screening candidate resumes against job criteria and computing rankings...';
  }
  if (elements.loadingSubtext) {
    elements.loadingSubtext.textContent = 'Eligible previously uploaded resumes are automatically screened against this position before computing rankings.';
  }

  if (isLoading) {
    if (elements.emptyState) elements.emptyState.style.display = 'none';
    if (elements.leaderboardContent) elements.leaderboardContent.style.display = 'none';
  }
}

/**
 * 7. Update KPI Metrics & Tab Badges
 */
function updateKpis() {
  const total = state.rankings.length;
  const selected = state.rankings.filter(r => r.reviewStatus === 'SELECTED').length;
  const rejected = state.rankings.filter(r => r.reviewStatus === 'REJECTED').length;
  const overrides = state.rankings.filter(r => r.isManualOverride === true || r.reviewStatus === 'REVIEWED').length;

  if (elements.totalApplicants) elements.totalApplicants.textContent = total;
  if (elements.selectedCount) elements.selectedCount.textContent = selected;
  if (elements.rejectedCount) elements.rejectedCount.textContent = rejected;
  if (elements.overrideCount) elements.overrideCount.textContent = overrides;

  // Header & Tab Badges
  if (elements.leaderboardTotalBadge) elements.leaderboardTotalBadge.textContent = `${total} Candidates`;
  if (elements.tabBadgeAll) elements.tabBadgeAll.textContent = total;
  if (elements.tabBadgeSelected) elements.tabBadgeSelected.textContent = selected;
  if (elements.tabBadgeRejected) elements.tabBadgeRejected.textContent = rejected;
  if (elements.tabBadgeOverride) elements.tabBadgeOverride.textContent = overrides;

  if (elements.selectedColumnBadge) elements.selectedColumnBadge.textContent = `${selected} Selected`;
  if (elements.rejectedColumnBadge) elements.rejectedColumnBadge.textContent = `${rejected} Rejected`;
}

/**
 * 9. Filter Tab Switcher
 */
function setActiveFilterTab(filter) {
  state.currentFilter = filter;

  const tabs = [
    { tab: elements.tabAll, filterName: 'all' },
    { tab: elements.tabSelected, filterName: 'selected' },
    { tab: elements.tabRejected, filterName: 'rejected' },
    { tab: elements.tabOverride, filterName: 'override' }
  ];

  tabs.forEach(item => {
    if (item.tab) {
      const isActive = item.filterName === filter;
      item.tab.classList.toggle('is-active', isActive);
      item.tab.setAttribute('aria-selected', isActive ? 'true' : 'false');
    }
  });

  renderLeaderboard();
}

/**
 * Filter rankings according to text search, score range, experience, review status, and skill keyword.
 * (Phase 4B - Step 5)
 *
 * @returns {Array} Filtered subset of state.rankings
 */
function getFilteredRankings() {
  if (!Array.isArray(state.rankings) || state.rankings.length === 0) {
    return [];
  }

  const { searchQuery, minScore, maxScore, minExperience, reviewStatus, skillKeyword } = state.filters;
  const q = (searchQuery || '').trim().toLowerCase();
  const skillQ = (skillKeyword || '').trim().toLowerCase();
  const minScoreNum = minScore !== null && minScore !== '' && !isNaN(Number(minScore)) ? Number(minScore) : null;
  const maxScoreNum = maxScore !== null && maxScore !== '' && !isNaN(Number(maxScore)) ? Number(maxScore) : null;
  const minExpNum = minExperience !== 'any' && !isNaN(Number(minExperience)) ? Number(minExperience) : null;
  const statusFilter = (reviewStatus || 'all').toUpperCase();

  // Validate range if both set
  const isRangeInvalid = minScoreNum !== null && maxScoreNum !== null && minScoreNum > maxScoreNum;
  if (elements.filterRangeError) {
    elements.filterRangeError.style.display = isRangeInvalid ? 'block' : 'none';
  }

  return state.rankings.filter(ranking => {
    // 1. Text Search: name, email, matchedSkills, decisionReason
    if (q) {
      const name = (ranking.candidateName || '').toLowerCase();
      const email = (ranking.candidateEmail || '').toLowerCase();
      const skills = Array.isArray(ranking.matchedSkills) ? ranking.matchedSkills.join(' ').toLowerCase() : (ranking.matchedSkills || '').toLowerCase();
      const reason = (ranking.decisionReason || '').toLowerCase();

      const matchesSearch = name.includes(q) || email.includes(q) || skills.includes(q) || reason.includes(q);
      if (!matchesSearch) return false;
    }

    // 2. Score Range Filter
    const score = Number(ranking.finalScore || 0);
    if (!isRangeInvalid) {
      if (minScoreNum !== null && score < minScoreNum) return false;
      if (maxScoreNum !== null && score > maxScoreNum) return false;
    }

    // 3. Minimum Experience Filter
    if (minExpNum !== null) {
      const exp = (ranking.experienceYears !== null && ranking.experienceYears !== undefined)
        ? Number(ranking.experienceYears)
        : 0;
      if (exp < minExpNum) return false;
    }

    // 4. Review Status Filter
    if (statusFilter !== 'ALL') {
      const rankingStatus = (ranking.reviewStatus || '').toUpperCase();
      if (rankingStatus !== statusFilter) return false;
    }

    // 5. Skill Keyword Filter
    if (skillQ) {
      const skills = Array.isArray(ranking.matchedSkills) ? ranking.matchedSkills.join(' ').toLowerCase() : (ranking.matchedSkills || '').toLowerCase();
      if (!skills.includes(skillQ)) return false;
    }

    return true;
  });
}

/**
 * Updates filter badge, chips, clear button, and summary text.
 * (Phase 4B - Step 5)
 */
function updateFilterToolbarUi(matchingCount, totalCount) {
  const { searchQuery, minScore, maxScore, minExperience, reviewStatus, skillKeyword } = state.filters;
  const q = (searchQuery || '').trim();
  const skillQ = (skillKeyword || '').trim();
  const minScoreNum = minScore !== null && minScore !== '' && !isNaN(Number(minScore)) ? Number(minScore) : null;
  const maxScoreNum = maxScore !== null && maxScore !== '' && !isNaN(Number(maxScore)) ? Number(maxScore) : null;
  const minExpNum = minExperience !== 'any' && !isNaN(Number(minExperience)) ? Number(minExperience) : null;
  const statusFilter = (reviewStatus || 'all').toUpperCase();

  let activeFilterCount = 0;
  const chips = [];

  if (q) {
    activeFilterCount++;
    chips.push({
      id: 'search',
      label: `Search: "${q}"`,
      clear: () => {
        state.filters.searchQuery = '';
        if (elements.candidateSearchInput) elements.candidateSearchInput.value = '';
        if (elements.searchClearBtn) elements.searchClearBtn.style.display = 'none';
        renderLeaderboard();
      }
    });
  }

  if (minScoreNum !== null) {
    activeFilterCount++;
    chips.push({
      id: 'minScore',
      label: `Min Score: ≥${minScoreNum}%`,
      clear: () => {
        state.filters.minScore = null;
        if (elements.filterMinScore) elements.filterMinScore.value = '';
        renderLeaderboard();
      }
    });
  }

  if (maxScoreNum !== null) {
    activeFilterCount++;
    chips.push({
      id: 'maxScore',
      label: `Max Score: ≤${maxScoreNum}%`,
      clear: () => {
        state.filters.maxScore = null;
        if (elements.filterMaxScore) elements.filterMaxScore.value = '';
        renderLeaderboard();
      }
    });
  }

  if (minExpNum !== null) {
    activeFilterCount++;
    chips.push({
      id: 'minExperience',
      label: `Exp: ≥${minExpNum} yr${minExpNum > 1 ? 's' : ''}`,
      clear: () => {
        state.filters.minExperience = 'any';
        if (elements.filterMinExperience) elements.filterMinExperience.value = 'any';
        renderLeaderboard();
      }
    });
  }

  if (statusFilter !== 'ALL') {
    activeFilterCount++;
    chips.push({
      id: 'reviewStatus',
      label: `Status: ${reviewStatus}`,
      clear: () => {
        state.filters.reviewStatus = 'all';
        if (elements.filterReviewStatus) elements.filterReviewStatus.value = 'all';
        renderLeaderboard();
      }
    });
  }

  if (skillQ) {
    activeFilterCount++;
    chips.push({
      id: 'skillKeyword',
      label: `Skill: ${skillQ}`,
      clear: () => {
        state.filters.skillKeyword = '';
        if (elements.filterSkillKeyword) elements.filterSkillKeyword.value = '';
        renderLeaderboard();
      }
    });
  }

  // Active filter count badge
  if (elements.activeFilterCountBadge) {
    if (activeFilterCount > 0) {
      elements.activeFilterCountBadge.textContent = activeFilterCount;
      elements.activeFilterCountBadge.style.display = 'inline-block';
    } else {
      elements.activeFilterCountBadge.style.display = 'none';
    }
  }

  // Clear all button visibility
  if (elements.clearAllFiltersBtn) {
    elements.clearAllFiltersBtn.style.display = activeFilterCount > 0 ? 'inline-flex' : 'none';
  }

  // Update CSV Export Badges & Counts (Phase 4B - Step 6)
  if (elements.exportCountBadge) {
    elements.exportCountBadge.textContent = matchingCount;
  }
  if (elements.exportFilteredCountLabel) {
    elements.exportFilteredCountLabel.textContent = matchingCount;
  }
  if (elements.exportAllCountLabel) {
    elements.exportAllCountLabel.textContent = totalCount;
  }
  if (elements.exportCsvBtn) {
    elements.exportCsvBtn.disabled = (totalCount === 0);
  }

  // Search input clear icon
  if (elements.searchClearBtn) {
    elements.searchClearBtn.style.display = q.length > 0 ? 'flex' : 'none';
  }

  // Results summary bar
  if (elements.filterResultsSummary) {
    if (activeFilterCount > 0) {
      elements.filterResultsSummary.style.display = 'flex';
      if (elements.filterMatchingCount) elements.filterMatchingCount.textContent = matchingCount;
      if (elements.filterTotalCount) elements.filterTotalCount.textContent = totalCount;

      if (elements.filterChipsList) {
        elements.filterChipsList.innerHTML = '';
        chips.forEach(chip => {
          const chipEl = document.createElement('span');
          chipEl.className = 'filter-chip';
          chipEl.innerHTML = `
            <span>${escapeHtml(chip.label)}</span>
            <button type="button" class="filter-chip-remove" aria-label="Remove filter ${escapeHtml(chip.label)}" title="Remove filter">&times;</button>
          `;
          chipEl.querySelector('.filter-chip-remove').addEventListener('click', chip.clear);
          elements.filterChipsList.appendChild(chipEl);
        });
      }
    } else {
      elements.filterResultsSummary.style.display = 'none';
    }
  }
}

/**
 * Escapes a cell value for CSV output following RFC 4180 and protecting against formula injection.
 * (Phase 4B - Step 6)
 *
 * @param {string|number|null} value
 * @returns {string} Escaped CSV cell value
 */
function escapeCsvCell(value) {
  if (value === null || value === undefined) return '""';
  let str = String(value);

  // Neutralize spreadsheet formula injection characters
  if (str.length > 0) {
    const firstChar = str.charAt(0);
    if (firstChar === '=' || firstChar === '+' || firstChar === '-' || firstChar === '@' || firstChar === '\t' || firstChar === '\r') {
      str = "'" + str;
    }
  }

  const needsQuotes = str.includes(',') || str.includes('"') || str.includes('\n') || str.includes('\r') || str.startsWith("'");
  if (needsQuotes) {
    str = str.replace(/"/g, '""');
    return `"${str}"`;
  }
  return str;
}

/**
 * Generates an RFC 4180-compliant, formula-injection protected CSV directly in the browser
 * for the currently filtered set of candidates and triggers immediate download.
 * (Phase 4B - Step 6)
 */
function exportCurrentFilteredView() {
  const filtered = getFilteredRankings();
  if (!filtered || filtered.length === 0) {
    showToast('No candidates available to export.', 'info');
    return;
  }

  const job = state.jobs.find(j => String(j.id) === String(state.selectedJobId));
  const jobTitle = job ? job.title : 'Position';

  const headers = [
    "Rank Position",
    "Candidate Name",
    "Email",
    "Phone",
    "Overall Match Score (%)",
    "Review Status",
    "Original AI Recommendation",
    "Manual Override",
    "Years of Experience",
    "Skills Match Score (%)",
    "Experience Match Score (%)",
    "Matched Skills",
    "Missing Skills",
    "AI Decision Rationale",
    "Recruiter Justification Notes",
    "Target Job Title",
    "Resume File Name",
    "Ranked At"
  ];

  const rows = filtered.map(r => {
    const originalRec = (r.decisionReason && r.decisionReason.trim().toUpperCase().startsWith('SELECTED')) ? 'SELECTED' : 'REJECTED';
    const isOverride = r.isManualOverride === true ? 'YES' : 'NO';
    const overallScore = Number(r.finalScore || 0).toFixed(1);
    const skillsScore = r.skillsScore !== null && r.skillsScore !== undefined ? Number(r.skillsScore).toFixed(1) : 'N/A';
    const expScore = r.experienceScore !== null && r.experienceScore !== undefined ? Number(r.experienceScore).toFixed(1) : 'N/A';
    const expYears = r.experienceYears !== null && r.experienceYears !== undefined ? String(r.experienceYears) : 'N/A';
    const matched = Array.isArray(r.matchedSkills) ? r.matchedSkills.join('; ') : (r.matchedSkills || 'None');
    const missing = Array.isArray(r.missingSkills) ? r.missingSkills.join('; ') : (r.missingSkills || 'None');

    return [
      escapeCsvCell(r.rankPosition),
      escapeCsvCell(r.candidateName),
      escapeCsvCell(r.candidateEmail),
      escapeCsvCell(r.candidatePhone || 'N/A'),
      escapeCsvCell(overallScore),
      escapeCsvCell(r.reviewStatus),
      escapeCsvCell(originalRec),
      escapeCsvCell(isOverride),
      escapeCsvCell(expYears),
      escapeCsvCell(skillsScore),
      escapeCsvCell(expScore),
      escapeCsvCell(matched),
      escapeCsvCell(missing),
      escapeCsvCell(r.decisionReason),
      escapeCsvCell(r.reviewerNotes || ''),
      escapeCsvCell(r.jobTitle || jobTitle),
      escapeCsvCell(r.resumeFileName || 'N/A'),
      escapeCsvCell(r.rankedAt || '')
    ].join(',');
  });

  const csvString = [headers.join(','), ...rows].join('\r\n');
  const bom = '\uFEFF';
  const blob = new Blob([bom, csvString], { type: 'text/csv;charset=utf-8;' });

  const now = new Date();
  const timestamp = now.toISOString().replace(/[-:T]/g, '').slice(0, 14);
  const safeJobSlug = jobTitle.toLowerCase().replace(/[^a-z0-9]+/g, '_');
  const filename = `candidates_filtered_${safeJobSlug}_${timestamp}.csv`;

  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.setAttribute('download', filename);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(link.href);

  showToast(`Exported ${filtered.length} filtered candidate(s) to CSV.`, 'success');
}

/**
 * Downloads the full candidate leaderboard CSV from the Spring Boot backend endpoint.
 * (Phase 4B - Step 6)
 */
function exportFullLeaderboard() {
  if (!state.selectedJobId) {
    showToast('Please select a job description first.', 'error');
    return;
  }
  if (state.rankings.length === 0) {
    showToast('No candidates available to export.', 'info');
    return;
  }

  const url = window.api.getExportCsvUrl(state.selectedJobId);
  const link = document.createElement('a');
  link.href = url;
  link.setAttribute('download', '');
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);

  showToast('Downloading full candidate evaluation CSV...', 'info');
}

/**
 * Resets all search and advanced filter controls to defaults.
 * (Phase 4B - Step 5)
 */
function clearAllFilters() {
  state.filters.searchQuery = '';
  state.filters.minScore = null;
  state.filters.maxScore = null;
  state.filters.minExperience = 'any';
  state.filters.reviewStatus = 'all';
  state.filters.skillKeyword = '';

  if (elements.candidateSearchInput) elements.candidateSearchInput.value = '';
  if (elements.searchClearBtn) elements.searchClearBtn.style.display = 'none';
  if (elements.filterMinScore) elements.filterMinScore.value = '';
  if (elements.filterMaxScore) elements.filterMaxScore.value = '';
  if (elements.filterMinExperience) elements.filterMinExperience.value = 'any';
  if (elements.filterReviewStatus) elements.filterReviewStatus.value = 'all';
  if (elements.filterSkillKeyword) elements.filterSkillKeyword.value = '';
  if (elements.filterRangeError) elements.filterRangeError.style.display = 'none';

  renderLeaderboard();
}

/**
 * 8, 10 & 12. Render Candidate Cards into Selected and Rejected Columns
 */
function renderLeaderboard() {
  if (state.rankings.length === 0) {
    if (elements.leaderboardFilterToolbar) elements.leaderboardFilterToolbar.style.display = 'none';
    return;
  }

  // Show filter toolbar whenever rankings exist
  if (elements.leaderboardFilterToolbar) elements.leaderboardFilterToolbar.style.display = 'flex';

  const filteredRankings = getFilteredRankings();
  const filter = state.currentFilter;

  updateFilterToolbarUi(filteredRankings.length, state.rankings.length);

  // If search/filters produced zero matching candidates, show dedicated empty state
  if (filteredRankings.length === 0) {
    if (elements.leaderboardContent) elements.leaderboardContent.style.display = 'none';
    if (elements.emptyState) {
      elements.emptyState.style.display = 'flex';
      if (elements.emptyStateTitle) elements.emptyStateTitle.textContent = 'No Candidates Match Filter Criteria';
      if (elements.emptyStateDesc) {
        elements.emptyStateDesc.textContent = `None of the ${state.rankings.length} evaluated candidate(s) matched your current search query or filter constraints. Adjust your terms or clear filters to view all applicants.`;
      }
      if (elements.emptyStateActions) {
        elements.emptyStateActions.style.display = 'block';
        elements.emptyStateActions.innerHTML = '';
        const resetBtn = document.createElement('button');
        resetBtn.type = 'button';
        resetBtn.className = 'btn btn-secondary btn-sm';
        resetBtn.textContent = 'Clear All Filters';
        resetBtn.addEventListener('click', clearAllFilters);
        elements.emptyStateActions.appendChild(resetBtn);
      }
    }
    return;
  }

  // Candidate matches found: restore content view and hide initial empty state
  if (elements.emptyState) elements.emptyState.style.display = 'none';
  if (elements.leaderboardContent) elements.leaderboardContent.style.display = 'grid';

  // Filter lists based on active tab from the filtered rankings set
  let selectedItems = filteredRankings.filter(r => r.reviewStatus === 'SELECTED');
  let rejectedItems = filteredRankings.filter(r => r.reviewStatus !== 'SELECTED');

  if (filter === 'selected') {
    rejectedItems = [];
    if (elements.selectedColumn) elements.selectedColumn.style.display = 'flex';
    if (elements.rejectedColumn) elements.rejectedColumn.style.display = 'none';
  } else if (filter === 'rejected') {
    selectedItems = [];
    if (elements.selectedColumn) elements.selectedColumn.style.display = 'none';
    if (elements.rejectedColumn) elements.rejectedColumn.style.display = 'flex';
  } else if (filter === 'override') {
    selectedItems = selectedItems.filter(r => r.isManualOverride === true || r.reviewStatus === 'REVIEWED');
    rejectedItems = rejectedItems.filter(r => r.isManualOverride === true || r.reviewStatus === 'REVIEWED');
    if (elements.selectedColumn) elements.selectedColumn.style.display = 'flex';
    if (elements.rejectedColumn) elements.rejectedColumn.style.display = 'flex';
  } else {
    // 'all'
    if (elements.selectedColumn) elements.selectedColumn.style.display = 'flex';
    if (elements.rejectedColumn) elements.rejectedColumn.style.display = 'flex';
  }

  // Populate Selected List
  if (elements.selectedList) {
    elements.selectedList.innerHTML = '';
    if (selectedItems.length === 0) {
      const minScoreVal = elements.minScoreInput ? elements.minScoreInput.value : '20';
      const isThresholdIssue = state.rankings.length > 0 && filter !== 'override' && state.filters.searchQuery === '';
      const helpTitle = isThresholdIssue
        ? `No Candidates Met Minimum Score (${minScoreVal}%)`
        : 'No selected candidates in this view';
      const helpSub = isThresholdIssue
        ? `All ${state.rankings.length} evaluated applicant(s) scored below the minimum threshold. Lower the Min Match Score slider above and regenerate rankings to select more candidates.`
        : '';

      elements.selectedList.innerHTML = `
        <div class="empty-state" style="padding: var(--space-6); background-color: transparent; border-style: dotted;">
          <span class="empty-state-title" style="font-size: var(--text-sm);">${escapeHtml(helpTitle)}</span>
          ${helpSub ? `<p class="empty-state-text" style="font-size: var(--text-xs); margin-top: var(--space-1);">${escapeHtml(helpSub)}</p>` : ''}
        </div>
      `;
    } else {
      selectedItems.forEach(ranking => {
        elements.selectedList.appendChild(createCandidateCardElement(ranking));
      });
    }
  }

  // Populate Rejected List
  if (elements.rejectedList) {
    elements.rejectedList.innerHTML = '';
    if (rejectedItems.length === 0) {
      elements.rejectedList.innerHTML = `
        <div class="empty-state" style="padding: var(--space-6); background-color: transparent; border-style: dotted;">
          <span class="empty-state-title" style="font-size: var(--text-sm);">No rejected candidates in this view</span>
        </div>
      `;
    } else {
      rejectedItems.forEach(ranking => {
        elements.rejectedList.appendChild(createCandidateCardElement(ranking));
      });
    }
  }
}

/**
 * Creates a complete Candidate Card DOM element conforming to components.css styles.
 *
 * @param {Object} ranking - CandidateRankingResponseDto
 * @returns {HTMLElement} Card element
 */
function createCandidateCardElement(ranking) {
  const card = document.createElement('div');

  const isSelected = ranking.reviewStatus === 'SELECTED';
  const isRejected = ranking.reviewStatus === 'REJECTED';
  const isOverridden = ranking.isManualOverride === true;

  let accentClass = '';
  if (isOverridden) {
    accentClass = 'is-overridden';
  } else if (isSelected) {
    accentClass = 'is-selected';
  } else if (isRejected) {
    accentClass = 'is-rejected';
  }

  card.className = `candidate-card ${accentClass}`;
  card.id = `candidate-card-${ranking.id}`;

  // Determine Rank Badge Styling
  let rankStyleClass = '';
  if (ranking.rankPosition === 1) rankStyleClass = 'rank-top-1';
  else if (ranking.rankPosition === 2) rankStyleClass = 'rank-top-2';
  else if (ranking.rankPosition === 3) rankStyleClass = 'rank-top-3';

  // Format Match Score
  const rawScore = Number(ranking.finalScore || 0);
  const formattedScore = rawScore.toFixed(1);
  const clampedScore = Math.min(100, Math.max(0, rawScore));

  let scoreFillClass = 'fill-mid';
  if (clampedScore >= 70) scoreFillClass = 'fill-high';
  else if (clampedScore < 40) scoreFillClass = 'fill-low';

  // Status Badge Class
  let badgeClass = 'badge-reviewed';
  if (ranking.reviewStatus === 'SELECTED') badgeClass = 'badge-selected';
  else if (ranking.reviewStatus === 'REJECTED') badgeClass = 'badge-rejected';
  else if (ranking.reviewStatus === 'SHORTLISTED') badgeClass = 'badge-shortlisted';

  // Override Indicator Pill
  const overridePill = isOverridden
    ? `<span class="badge-override-indicator" title="Recruiter manual decision override applied">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
          <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
        </svg>
        Overridden
       </span>`
    : '';

  card.innerHTML = `
    <!-- Top Summary Row -->
    <div class="card-summary-row">
      
      <!-- Identity Block -->
      <div class="card-identity-group">
        <div class="rank-badge ${rankStyleClass}" title="Rank #${ranking.rankPosition}">
          #${ranking.rankPosition}
        </div>
        <div class="candidate-info-block">
          <span class="candidate-name">${escapeHtml(ranking.candidateName || 'Unnamed Candidate')}</span>
          <div style="display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-2); margin-top: 2px;">
            ${ranking.candidateEmail ? `<span class="candidate-meta-email" style="font-size: var(--text-xs); color: var(--color-primary); display: inline-flex; align-items: center; gap: 4px;">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z"></path>
                <polyline points="22,6 12,13 2,6"></polyline>
              </svg>
              ${escapeHtml(ranking.candidateEmail)}
            </span>` : ''}
            ${(ranking.candidatePhone && ranking.candidatePhone !== 'N/A') ? `<span class="candidate-meta-phone" style="font-size: var(--text-xs); color: var(--color-text-muted); display: inline-flex; align-items: center; gap: 4px;">
              <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path>
              </svg>
              ${escapeHtml(ranking.candidatePhone)}
            </span>` : ''}
            ${ranking.resumeFileName ? `<span class="candidate-meta-file" style="font-size: var(--text-xs); color: var(--color-text-muted); display: inline-flex; align-items: center; gap: 4px;" title="Original resume file name">
              <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
                <polyline points="14 2 14 8 20 8"></polyline>
              </svg>
              ${escapeHtml(ranking.resumeFileName)}
            </span>` : ''}
          </div>
          <span class="candidate-meta-id">Candidate ID: ${ranking.candidateId || 'N/A'} &bull; Resume #${ranking.resumeId}${ranking.experienceYears !== null && ranking.experienceYears !== undefined ? ` &bull; <strong>${ranking.experienceYears} yr${ranking.experienceYears === 1 ? '' : 's'} exp</strong>` : ''}</span>
        </div>
      </div>

      <!-- Metrics & Status Group -->
      <div class="card-metrics-group">
        
        <!-- Score Bar Indicator -->
        <div class="score-indicator-group" title="Overall Match Score: ${formattedScore}%">
          <div class="score-text-row">
            <span class="form-label-hint">Match</span>
            <span class="score-percent-val">${formattedScore}%</span>
          </div>
          <div class="score-bar-track" aria-hidden="true">
            <div class="score-bar-fill ${scoreFillClass}" style="width: ${clampedScore}%;"></div>
          </div>
        </div>

        <!-- Status Badges -->
        <span class="badge ${badgeClass}">${escapeHtml(ranking.reviewStatus)}</span>
        ${overridePill}

      </div>

      <!-- Action Toggles -->
      <div class="card-actions-group">
        <button type="button" class="btn btn-secondary btn-sm toggle-details-btn" aria-expanded="false" aria-controls="details-${ranking.id}">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" class="chevron-icon">
            <polyline points="6 9 12 15 18 9"></polyline>
          </svg>
          <span class="btn-toggle-label">Explain Decision</span>
        </button>
      </div>

    </div>

    <!-- Expandable Details Accordion Panel -->
    <div class="card-details-panel" id="details-${ranking.id}">
      
      <!-- 1. AI Decision Explanation Box -->
      <div class="decision-reason-box">
        <div class="reason-header">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="10"></circle>
            <line x1="12" y1="16" x2="12" y2="12"></line>
            <line x1="12" y1="8" x2="12.01" y2="8"></line>
          </svg>
          AI Decision Rationale
        </div>
        <p class="reason-text">${escapeHtml(ranking.decisionReason || 'Deterministic evaluation completed. Score met selection criteria.')}</p>
      </div>

      <!-- Narrative AI Summary Box (Phase 4B - Step 6) -->
      ${ranking.aiSummary ? `
      <div class="ai-narrative-summary-box" style="margin-top: var(--space-2); padding: var(--space-2-5) var(--space-3); background-color: var(--color-bg-alt); border-left: 3px solid var(--color-primary); border-radius: var(--radius-sm); font-size: var(--text-xs); color: var(--color-text-main); line-height: 1.5;">
        <div style="font-weight: var(--font-semibold); margin-bottom: 3px; color: var(--color-primary); display: flex; align-items: center; gap: 4px;">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"></polygon>
          </svg>
          Screening AI Narrative Analysis
        </div>
        <div>${escapeHtml(ranking.aiSummary)}</div>
      </div>` : ''}

      <!-- 2. Verified Skills Breakdown Section -->
      <div class="skills-breakdown-section" id="skills-section-${ranking.id}">
        <span class="form-label-hint">Verified Skills &amp; Competencies:</span>
        <div class="skills-tags-container" id="skills-container-${ranking.id}" style="margin-top: var(--space-1-5);">
          ${(Array.isArray(ranking.matchedSkills) && ranking.matchedSkills.length > 0) ? ranking.matchedSkills.map(skill => `<span class="skill-tag skill-matched">${escapeHtml(skill)}</span>`).join(' ') : (typeof ranking.matchedSkills === 'string' && ranking.matchedSkills.trim()) ? ranking.matchedSkills.split(',').map(s => s.trim()).filter(Boolean).map(skill => `<span class="skill-tag skill-matched">${escapeHtml(skill)}</span>`).join(' ') : '<span class="form-label-hint">Click Explain Decision to view skills breakdown</span>'}
        </div>
      </div>

      <!-- 3. Manual Recruiter Override Drawer -->
      <div class="override-drawer">
        <div class="override-header">
          <span class="override-title">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: inline; vertical-align: middle; margin-right: 4px;" aria-hidden="true">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
            </svg>
            Recruiter Override Controls
          </span>
          ${isOverridden ? '<span class="badge-override-indicator">Override Active</span>' : ''}
        </div>

        <div class="override-fields-grid">
          <div class="form-group">
            <label class="form-label" for="status-select-${ranking.id}">Review Status</label>
            <select class="form-select form-select-sm" id="status-select-${ranking.id}">
              <option value="SELECTED" ${ranking.reviewStatus === 'SELECTED' ? 'selected' : ''}>SELECTED</option>
              <option value="SHORTLISTED" ${ranking.reviewStatus === 'SHORTLISTED' ? 'selected' : ''}>SHORTLISTED</option>
              <option value="REVIEWED" ${ranking.reviewStatus === 'REVIEWED' ? 'selected' : ''}>REVIEWED</option>
              <option value="REJECTED" ${ranking.reviewStatus === 'REJECTED' ? 'selected' : ''}>REJECTED</option>
            </select>
          </div>

          <div class="form-group">
            <label class="form-label" for="notes-input-${ranking.id}">Recruiter Justification Notes</label>
            <input type="text" class="form-control form-control-sm" id="notes-input-${ranking.id}" placeholder="Enter notes or override justification..." value="${escapeHtml(ranking.reviewerNotes || '')}">
          </div>

          <div class="form-group">
            <button type="button" class="btn btn-primary btn-sm save-override-btn" data-ranking-id="${ranking.id}" id="save-override-btn-${ranking.id}">
              Save Decision
            </button>
          </div>
        </div>
      </div>

    </div>
  `;

  // Attach Card Event Listeners
  const toggleBtn = card.querySelector('.toggle-details-btn');
  const detailsPanel = card.querySelector('.card-details-panel');
  const btnLabel = card.querySelector('.btn-toggle-label');
  const chevronIcon = card.querySelector('.chevron-icon');

  if (toggleBtn && detailsPanel) {
    toggleBtn.addEventListener('click', () => {
      const isExpanded = card.classList.toggle('is-expanded');
      toggleBtn.setAttribute('aria-expanded', isExpanded ? 'true' : 'false');
      if (btnLabel) {
        btnLabel.textContent = isExpanded ? 'Hide Details' : 'Explain Decision';
      }
      if (chevronIcon) {
        chevronIcon.style.transform = isExpanded ? 'rotate(180deg)' : 'rotate(0deg)';
        chevronIcon.style.transition = 'transform var(--transition-fast)';
      }

      // If opening, lazily fetch verified screening skills details
      if (isExpanded && ranking.screeningResultId) {
        loadCandidateSkills(ranking.screeningResultId, ranking.id);
      }
    });
  }

  // Attach Save Override Button Listener
  const saveBtn = card.querySelector('.save-override-btn');
  if (saveBtn) {
    saveBtn.addEventListener('click', () => {
      handleSaveOverride(ranking.id);
    });
  }

  return card;
}

/**
 * 10. Fetch & Render Semantic Screening Skills (Matched vs Missing)
 *
 * @param {number} screeningResultId - ID of screening result
 * @param {number} rankingId - ID of candidate ranking record
 */
async function loadCandidateSkills(screeningResultId, rankingId) {
  const container = document.getElementById(`skills-container-${rankingId}`);
  if (!container) return;

  // Use cached screening result if available
  if (state.screeningCache.has(screeningResultId)) {
    renderSkillsTags(state.screeningCache.get(screeningResultId), container);
    return;
  }

  try {
    container.innerHTML = '<span class="form-label-hint">Loading screening competencies...</span>';
    const screening = await window.api.getScreeningResultById(screeningResultId);
    state.screeningCache.set(screeningResultId, screening);
    renderSkillsTags(screening, container);
  } catch (err) {
    container.innerHTML = '<span class="form-label-hint">Detailed skills metadata unavailable.</span>';
  }
}

/**
 * Helper to render matched and missing skill tags.
 */
function renderSkillsTags(screening, container) {
  if (!screening) {
    container.innerHTML = '<span class="form-label-hint">No skills data available.</span>';
    return;
  }

  const matched = Array.isArray(screening.matchedSkills) ? screening.matchedSkills : [];
  const missing = Array.isArray(screening.missingSkills) ? screening.missingSkills : [];

  if (matched.length === 0 && missing.length === 0) {
    container.innerHTML = '<span class="form-label-hint">No specific keywords indexed.</span>';
    return;
  }

  let html = '<div class="skills-tags-wrap">';

  matched.forEach(skill => {
    html += `<span class="skill-tag matched" title="Verified matching skill">&#10003; ${escapeHtml(skill)}</span>`;
  });

  missing.forEach(skill => {
    html += `<span class="skill-tag missing" title="Missing required skill">&#10005; ${escapeHtml(skill)}</span>`;
  });

  html += '</div>';

  if (screening.skillsScore !== null && screening.skillsScore !== undefined) {
    const sScore = Number(screening.skillsScore).toFixed(1);
    const expScore = screening.experienceScore !== null && screening.experienceScore !== undefined
      ? Number(screening.experienceScore).toFixed(1)
      : 'N/A';

    html += `
      <div style="font-size: var(--text-xs); color: var(--color-text-muted); margin-top: var(--space-2);">
        Skills Match Score: <strong>${sScore}%</strong> &bull; Experience Match Score: <strong>${expScore}%</strong>
      </div>
    `;
  }

  container.innerHTML = html;
}

/**
 * 11. Handle Recruiter Decision Override (PATCH API)
 *
 * @param {number} rankingId - ID of candidate ranking record
 */
async function handleSaveOverride(rankingId) {
  const statusSelect = document.getElementById(`status-select-${rankingId}`);
  const notesInput = document.getElementById(`notes-input-${rankingId}`);
  const saveBtn = document.getElementById(`save-override-btn-${rankingId}`);

  if (!statusSelect) return;

  const newStatus = statusSelect.value;
  const newNotes = notesInput ? notesInput.value.trim() : '';

  if (saveBtn) {
    saveBtn.disabled = true;
    saveBtn.textContent = 'Saving...';
  }

  try {
    const updatedRanking = await window.api.updateCandidateReview(rankingId, newStatus, newNotes);

    // Update state ranking record
    const index = state.rankings.findIndex(r => r.id === rankingId);
    if (index !== -1) {
      state.rankings[index] = updatedRanking;
    }

    // In-place card update to preserve open accordion and scroll position (Phase 4B - Step 6)
    const card = document.getElementById(`candidate-card-${rankingId}`);
    if (card) {
      card.classList.add('is-overridden');
      if (newStatus === 'SELECTED') {
        card.classList.add('is-selected');
        card.classList.remove('is-rejected');
      } else if (newStatus === 'REJECTED') {
        card.classList.add('is-rejected');
        card.classList.remove('is-selected');
      }

      // Update status badge
      const statusBadge = card.querySelector('.badge');
      if (statusBadge) {
        statusBadge.className = `badge badge-${newStatus.toLowerCase()}`;
        statusBadge.textContent = newStatus;
      }

      // Ensure override indicator pill is present
      const metricsGroup = card.querySelector('.card-metrics-group');
      if (metricsGroup && !metricsGroup.querySelector('.badge-override-indicator')) {
        const pill = document.createElement('span');
        pill.className = 'badge-override-indicator';
        pill.title = 'Recruiter manual decision override applied';
        pill.innerHTML = `
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
          </svg>
          Overridden
        `;
        metricsGroup.appendChild(pill);
      }

      // Handle active filter mismatch notification clearly
      const activeTab = state.currentFilter;
      const isNowSelected = newStatus === 'SELECTED';
      const mismatch = (activeTab === 'selected' && !isNowSelected) || (activeTab === 'rejected' && isNowSelected);
      let noticeEl = card.querySelector('.filter-mismatch-notice');
      if (mismatch) {
        if (!noticeEl) {
          noticeEl = document.createElement('div');
          noticeEl.className = 'filter-mismatch-notice';
          noticeEl.style.cssText = 'margin-top: var(--space-2); padding: 6px 10px; font-size: var(--text-xs); color: #92400e; background-color: #fef3c7; border: 1px solid #fde68a; border-radius: var(--radius-sm); display: flex; align-items: center; gap: 6px;';
          card.querySelector('.card-details-panel').prepend(noticeEl);
        }
        noticeEl.innerHTML = `
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>
          <span>Status changed to <strong>${escapeHtml(newStatus)}</strong> (candidate will move to ${isNowSelected ? 'Selected' : 'Rejected'} column on tab switch).</span>
        `;
      } else if (noticeEl) {
        noticeEl.remove();
      }

      if (saveBtn) {
        saveBtn.disabled = false;
        saveBtn.textContent = 'Save Decision';
      }
    }

    // Refresh KPI metrics and toolbar counters without rebuilding cards
    updateKpis();
    const filtered = getFilteredRankings();
    updateFilterToolbarUi(filtered.length, state.rankings.length);

    showToast(`Decision updated to ${newStatus} for candidate.`, 'success');
  } catch (err) {
    showErrorBanner('Override Failed', err.message || 'Could not update review status.');
    showToast(err.message || 'Failed to update review status.', 'error');
    if (saveBtn) {
      saveBtn.disabled = false;
      saveBtn.textContent = 'Save Decision';
    }
  }
}

/**
 * Helper to show the initial empty state card with custom messaging and optional contextual action.
 *
 * @param {string} title
 * @param {string} desc
 * @param {string|null} [actionType=null] - 'upload' | 'adjust-threshold' | null
 */
function showInitialEmptyState(title, desc, actionType = null) {
  if (elements.leaderboardFilterToolbar) elements.leaderboardFilterToolbar.style.display = 'none';
  if (elements.emptyStateTitle) elements.emptyStateTitle.textContent = title;
  if (elements.emptyStateDesc) elements.emptyStateDesc.textContent = desc;

  if (elements.emptyStateActions) {
    elements.emptyStateActions.innerHTML = '';
    if (actionType === 'upload') {
      elements.emptyStateActions.style.display = 'block';
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn btn-secondary btn-sm';
      btn.id = 'empty-goto-upload-btn';
      btn.innerHTML = `
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
          <polyline points="17 8 12 3 7 8"></polyline>
          <line x1="12" y1="3" x2="12" y2="15"></line>
        </svg>
        <span>Go to Bulk Resume Upload</span>
      `;
      btn.addEventListener('click', () => {
        switchWorkspace('batches');
        if (elements.resumeDropzone) elements.resumeDropzone.focus();
      });
      elements.emptyStateActions.appendChild(btn);
    } else if (actionType === 'adjust-threshold') {
      elements.emptyStateActions.style.display = 'block';
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn btn-secondary btn-sm';
      btn.id = 'empty-adjust-threshold-btn';
      btn.innerHTML = `
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <line x1="4" y1="21" x2="4" y2="14"></line>
          <line x1="4" y1="10" x2="4" y2="3"></line>
          <line x1="12" y1="21" x2="12" y2="12"></line>
          <line x1="12" y1="8" x2="12" y2="3"></line>
          <line x1="20" y1="21" x2="20" y2="16"></line>
          <line x1="20" y1="12" x2="20" y2="3"></line>
          <line x1="1" y1="14" x2="7" y2="14"></line>
          <line x1="9" y1="8" x2="15" y2="8"></line>
          <line x1="17" y1="16" x2="23" y2="16"></line>
        </svg>
        <span>Adjust Minimum Score Threshold</span>
      `;
      btn.addEventListener('click', () => {
        if (elements.minScoreInput) {
          elements.minScoreInput.focus();
        }
      });
      elements.emptyStateActions.appendChild(btn);
    } else {
      elements.emptyStateActions.style.display = 'none';
    }
  }

  if (elements.emptyState) elements.emptyState.style.display = 'flex';
  if (elements.leaderboardContent) elements.leaderboardContent.style.display = 'none';
}

/* =============================================================================
   Recruiter Job Authoring & AI Skill Extraction Logic (Phase 4B - Step 1)
   ============================================================================= */

/**
 * Opens the Recruiter Job Authoring Modal and resets state.
 */
function openJobModal() {
  state.isJobModalOpen = true;
  state.jobModalSkills = [];
  state.isExtractingSkills = false;
  state.isSavingJob = false;

  // Reset form inputs
  if (elements.jobModalForm) elements.jobModalForm.reset();
  if (elements.jobModalInputMinExp) elements.jobModalInputMinExp.value = '0';
  if (elements.jobModalInputStatus) elements.jobModalInputStatus.value = 'ACTIVE';
  if (elements.jobModalCharCount) elements.jobModalCharCount.textContent = '0';
  if (elements.jobModalDescStats) elements.jobModalDescStats.classList.remove('near-limit');
  if (elements.jobModalCustomSkillInput) elements.jobModalCustomSkillInput.value = '';

  hideJobModalError();
  resetExtractSkillsBtnState();
  resetSaveJobBtnState();
  renderJobModalSkills();

  if (elements.jobModalBackdrop) {
    elements.jobModalBackdrop.classList.remove('is-hidden');
    document.body.style.overflow = 'hidden';
  }

  // Focus title input
  setTimeout(() => {
    if (elements.jobModalInputTitle) {
      elements.jobModalInputTitle.focus();
    }
  }, 50);
}

/**
 * Closes the Recruiter Job Authoring Modal.
 */
function closeJobModal() {
  state.isJobModalOpen = false;
  if (elements.jobModalBackdrop) {
    elements.jobModalBackdrop.classList.add('is-hidden');
    document.body.style.overflow = '';
  }
}

/**
 * Shows an in-modal validation or backend error banner.
 */
function showJobModalError(title, desc) {
  if (elements.jobModalErrorTitle) elements.jobModalErrorTitle.textContent = title;
  if (elements.jobModalErrorDesc) elements.jobModalErrorDesc.textContent = desc;
  if (elements.jobModalErrorBanner) {
    elements.jobModalErrorBanner.classList.remove('is-hidden');
    elements.jobModalErrorBanner.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }
}

/**
 * Hides the in-modal error banner.
 */
function hideJobModalError() {
  if (elements.jobModalErrorBanner) {
    elements.jobModalErrorBanner.classList.add('is-hidden');
  }
}

/**
 * Renders the skill chips inside the modal.
 */
function renderJobModalSkills() {
  if (!elements.jobModalSkillsWrap) return;

  if (elements.jobModalSkillsCount) {
    const count = state.jobModalSkills.length;
    elements.jobModalSkillsCount.textContent = `${count} skill${count === 1 ? '' : 's'}`;
  }

  if (state.jobModalSkills.length === 0) {
    elements.jobModalSkillsWrap.innerHTML = `
      <span class="skills-placeholder-text" id="job-modal-skills-empty-hint">
        No skills added yet. Paste a description above and click "Extract Skills with AI" or type a skill below.
      </span>
    `;
    return;
  }

  elements.jobModalSkillsWrap.innerHTML = '';
  state.jobModalSkills.forEach(skill => {
    const chip = document.createElement('span');
    chip.className = 'skill-chip';
    chip.innerHTML = `
      <span class="skill-chip-text">${escapeHtml(skill)}</span>
      <button type="button" class="skill-chip-remove" data-skill="${escapeHtml(skill)}" title="Remove ${escapeHtml(skill)}" aria-label="Remove skill ${escapeHtml(skill)}">
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <line x1="18" y1="6" x2="6" y2="18"></line>
          <line x1="6" y1="6" x2="18" y2="18"></line>
        </svg>
      </button>
    `;
    elements.jobModalSkillsWrap.appendChild(chip);
  });
}

/**
 * Adds a skill to the modal state if not already present.
 */
function addJobModalSkill(skillName) {
  if (!skillName || typeof skillName !== 'string') return false;
  const trimmed = skillName.trim();
  if (!trimmed) return false;

  const exists = state.jobModalSkills.some(s => s.toLowerCase() === trimmed.toLowerCase());
  if (exists) {
    showToast(`Skill "${trimmed}" is already added.`, 'info');
    return false;
  }

  state.jobModalSkills.push(trimmed);
  renderJobModalSkills();
  return true;
}

/**
 * Removes a skill from the modal state.
 */
function removeJobModalSkill(skillName) {
  if (!skillName) return;
  state.jobModalSkills = state.jobModalSkills.filter(s => s.toLowerCase() !== skillName.toLowerCase());
  renderJobModalSkills();
}

/**
 * Submits custom skill from manual text input.
 */
function submitCustomSkillFromInput() {
  if (!elements.jobModalCustomSkillInput) return;
  const val = elements.jobModalCustomSkillInput.value;
  if (addJobModalSkill(val)) {
    elements.jobModalCustomSkillInput.value = '';
    elements.jobModalCustomSkillInput.focus();
  }
}

/**
 * Resets the Extract Skills button to idle state.
 */
function resetExtractSkillsBtnState() {
  if (!elements.jobModalExtractBtn) return;
  elements.jobModalExtractBtn.disabled = false;
  if (elements.jobModalExtractText) elements.jobModalExtractText.textContent = 'Extract Skills with AI';
  if (elements.jobModalExtractIcon) {
    elements.jobModalExtractIcon.innerHTML = `
      <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"></path>
    `;
  }
}

/**
 * Resets the Save Job button to idle state.
 */
function resetSaveJobBtnState() {
  if (!elements.jobModalSaveBtn) return;
  elements.jobModalSaveBtn.disabled = false;
  if (elements.jobModalSaveText) elements.jobModalSaveText.textContent = 'Save Job Posting';
  if (elements.jobModalSaveIcon) {
    elements.jobModalSaveIcon.innerHTML = `
      <polyline points="20 6 9 17 4 12"></polyline>
    `;
  }
}

/**
 * Triggers AI skill extraction on job description text via Python microservice.
 */
async function handleExtractJobSkills() {
  const desc = elements.jobModalInputDesc ? elements.jobModalInputDesc.value.trim() : '';

  if (!desc) {
    showJobModalError('Description Text Required', 'Please paste or enter the job description text before extracting skills.');
    if (elements.jobModalInputDesc) elements.jobModalInputDesc.focus();
    return;
  }

  if (desc.length > 50000) {
    showJobModalError('Text Limit Exceeded', 'Job description text cannot exceed 50,000 characters.');
    return;
  }

  hideJobModalError();
  state.isExtractingSkills = true;

  if (elements.jobModalExtractBtn) elements.jobModalExtractBtn.disabled = true;
  if (elements.jobModalExtractText) elements.jobModalExtractText.textContent = 'Extracting Skills...';
  if (elements.jobModalExtractIcon) {
    elements.jobModalExtractIcon.innerHTML = `
      <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none" stroke-dasharray="32" stroke-linecap="round" style="animation: spin 0.8s linear infinite;"></circle>
    `;
  }

  try {
    const result = await window.api.extractJobSkills(desc);

    if (result && result.success && Array.isArray(result.skills)) {
      let newlyAdded = 0;
      result.skills.forEach(skill => {
        const trimmed = skill.trim();
        const exists = state.jobModalSkills.some(s => s.toLowerCase() === trimmed.toLowerCase());
        if (!exists) {
          state.jobModalSkills.push(trimmed);
          newlyAdded++;
        }
      });

      renderJobModalSkills();

      if (result.skills.length === 0) {
        showToast('AI analysis completed: No recognized IT skills were detected in the description. You can add required skills manually.', 'info', 4000);
      } else {
        showToast(`AI extracted ${result.skills.length} technical skills (${newlyAdded} newly added to job).`, 'success');
      }
    } else {
      const errMsg = (result && result.message) || 'Failed to extract skills from job description text.';
      showJobModalError('Skill Extraction Notice', errMsg);
      showToast(errMsg, 'error');
    }
  } catch (err) {
    const errMsg = err.message || 'Error communicating with AI skill extraction service.';
    showJobModalError('Skill Extraction Failed', errMsg);
    showToast(errMsg, 'error');
  } finally {
    state.isExtractingSkills = false;
    resetExtractSkillsBtnState();
  }
}

/**
 * Handles Job Posting form submission and persistence.
 */
async function handleSaveJob(e) {
  if (e && e.preventDefault) e.preventDefault();
  if (state.isSavingJob) return;

  const title = elements.jobModalInputTitle ? elements.jobModalInputTitle.value.trim() : '';
  const dept = elements.jobModalInputDept ? elements.jobModalInputDept.value.trim() : '';
  const location = elements.jobModalInputLocation ? elements.jobModalInputLocation.value.trim() : '';
  const minExpRaw = elements.jobModalInputMinExp ? elements.jobModalInputMinExp.value.trim() : '0';
  const status = elements.jobModalInputStatus ? elements.jobModalInputStatus.value.trim() : 'ACTIVE';
  const desc = elements.jobModalInputDesc ? elements.jobModalInputDesc.value.trim() : '';

  // Validation
  if (!title) {
    showJobModalError('Job Title Required', 'Please enter a valid job title.');
    if (elements.jobModalInputTitle) elements.jobModalInputTitle.focus();
    return;
  }

  if (title.length > 255) {
    showJobModalError('Job Title Too Long', 'Job title must not exceed 255 characters.');
    return;
  }

  if (!desc) {
    showJobModalError('Description Required', 'Job description text cannot be empty.');
    if (elements.jobModalInputDesc) elements.jobModalInputDesc.focus();
    return;
  }

  if (desc.length > 50000) {
    showJobModalError('Description Too Long', 'Job description text cannot exceed 50,000 characters.');
    return;
  }

  const minExp = parseInt(minExpRaw, 10);
  if (isNaN(minExp) || minExp < 0) {
    showJobModalError('Invalid Experience', 'Minimum experience years must be 0 or greater.');
    return;
  }

  hideJobModalError();
  state.isSavingJob = true;

  if (elements.jobModalSaveBtn) elements.jobModalSaveBtn.disabled = true;
  if (elements.jobModalSaveText) elements.jobModalSaveText.textContent = 'Saving Job...';
  if (elements.jobModalSaveIcon) {
    elements.jobModalSaveIcon.innerHTML = `
      <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none" stroke-dasharray="32" stroke-linecap="round" style="animation: spin 0.8s linear infinite;"></circle>
    `;
  }

  const payload = {
    title,
    department: dept || null,
    location: location || null,
    descriptionText: desc,
    requiredSkills: state.jobModalSkills,
    minExperienceYears: minExp,
    status: status || 'ACTIVE'
  };

  try {
    const createdJob = await window.api.createJob(payload);

    // Refresh jobs dropdown across both Bulk Upload and Leaderboard workspaces
    await loadJobs(createdJob.id);

    // If currently on leaderboard workspace or user wants to screen against it, update selected job
    state.selectedJobId = createdJob.id;
    if (elements.jobSelect) {
      elements.jobSelect.value = String(createdJob.id);
      // Trigger selection change to update leaderboard view state
      handleJobSelectionChange();
    }
    if (elements.batchJobSelect) {
      elements.batchJobSelect.value = String(createdJob.id);
    }

    closeJobModal();
    showToast(`Job posting "${createdJob.title}" created successfully with ${state.jobModalSkills.length} required skill(s).`, 'success', 4000);
  } catch (err) {
    const errMsg = err.message || 'Failed to save job posting to backend.';
    showJobModalError('Save Failed', errMsg);
    showToast(errMsg, 'error');
  } finally {
    state.isSavingJob = false;
    resetSaveJobBtnState();
  }
}
