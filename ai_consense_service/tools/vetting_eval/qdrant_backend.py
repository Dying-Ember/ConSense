"""One shared storage backend; changing it requires an explicitly verified index."""
from pathlib import Path


def connect(state, environ):
    from qdrant_client import QdrantClient
    url = environ.get('CONSENSE_QDRANT_URL')
    if url:
        return QdrantClient(url=url, api_key=environ.get('CONSENSE_QDRANT_API_KEY'), timeout=120)
    root = Path(state)
    root.mkdir(parents=True, exist_ok=True)
    return QdrantClient(path=str(root / 'qdrant'))
