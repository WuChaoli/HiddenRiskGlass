HiddenRiskGlassServer 离线部署包
================================

部署步骤：
1. 将本目录传输到目标离线服务器
2. 复制 .env.example 为 .env，填写 ADMIN_USERNAME、ADMIN_PASSWORD 和 SESSION_SECRET
3. 执行 ./start-offline.sh 启动服务
4. 访问 http://<服务器IP>:10203

停止服务：
  ./stop-offline.sh

查看日志：
  docker-compose logs -f

数据目录：
  /data/HiddenRiskGlass/data/
  （包含 SQLite 数据库和 releases/ APK 文件）
