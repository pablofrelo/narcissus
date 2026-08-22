FROM python:3.12-slim
RUN pip install --no-cache-dir fastapi uvicorn pyproj
WORKDIR /app
COPY narcissus_sync.py elevation.py .
VOLUME /data
EXPOSE 8765
CMD ["uvicorn", "narcissus_sync:app", "--host", "0.0.0.0", "--port", "8765"]
