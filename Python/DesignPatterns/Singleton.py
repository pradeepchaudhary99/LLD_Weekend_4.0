import threading
from typing import Optional


class ConfigurationManager:
    _instance: Optional["ConfigurationManager"] = None
    _lock = threading.Lock()

    def __init__(self) -> None:
        self.path: Optional[str] = None
        self.current_directory: Optional[str] = None
        self.metadata: Optional[str] = None

    @classmethod
    def get_instance(cls) -> "ConfigurationManager":
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = ConfigurationManager()
        return cls._instance


def main() -> None:
    ConfigurationManager.get_instance()


if __name__ == "__main__":
    main()
