# 基于 Debian Bookworm slim，glibc 兼容性好
FROM python:3.12-slim-bookworm

# 安装基本工具（调试用）
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 创建非 root 用户/组（UID/GID=1000）
RUN groupadd --gid 1000 appgroup && \
    useradd --uid 1000 --gid appgroup --shell /bin/bash --create-home appuser

# 设置工作目录
WORKDIR /app

# 先复制依赖文件并安装（利用 Docker 层缓存）
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制项目代码
COPY app/ ./app/
COPY server.py .
COPY config.json .

# 创建数据目录并设置属主
RUN mkdir -p /app/data/releases && chown -R appuser:appgroup /app/data

# 切换到非 root 用户
USER appuser

# 暴露服务端口
EXPOSE 10203

# 默认环境变量（可被 docker-compose 覆盖）
ENV APK_UPDATE_DATA_DIR=/app/data

# 启动命令
CMD ["python", "server.py", "--host", "0.0.0.0", "--port", "10203"]
