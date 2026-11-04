# =============================================================================
# Intelligent Resume Screening System - Frontend Web Client
# Lightweight Nginx Alpine Container
# =============================================================================

FROM nginx:1.27-alpine

# Remove default Nginx welcome site
RUN rm -rf /usr/share/nginx/html/*

# Copy custom reverse proxy and caching configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy web application static assets
COPY index.html login.html register.html /usr/share/nginx/html/
COPY css/ /usr/share/nginx/html/css/
COPY js/ /usr/share/nginx/html/js/
RUN mkdir -p /usr/share/nginx/html/assets

EXPOSE 80

# Health check verifying Nginx is actively serving HTTP requests
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q --spider http://localhost/ || exit 1

CMD ["nginx", "-g", "daemon off;"]

