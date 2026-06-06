# AI Translation Assistant

AI 翻译助手，采用前后端分离的桌面应用架构。

## 技术栈

- 后端：Java 17、Spring Boot、Maven
- 前端：Electron、Vue 3、Vite、TypeScript、Pinia

## 目录结构

```text
backend/   Spring Boot 后端服务
frontend/  Electron + Vue 桌面端
```

后端保留基础分层目录：

```text
config/
controller/
domain/
repository/
service/
```

## 本地开发

后端：

```bash
cd backend
mvn spring-boot:run
```

前端：

```bash
cd frontend
npm install
npm run dev
```
