from pathlib import Path


class ModelFolders:
    """Read-only folder browser confined to an operator-selected directory."""
    def __init__(self, root: Path):
        self.root = root.expanduser().resolve(strict=True)
        if not self.root.is_dir():
            raise ValueError("Models root must be a directory")

    def resolve(self, relative: str) -> Path:
        if not isinstance(relative, str) or len(relative) > 1024 or "\x00" in relative:
            raise ValueError("Invalid model folder")
        if Path(relative).is_absolute() or ".." in Path(relative).parts or "\\" in relative:
            raise ValueError("Choose a folder inside the configured models root")
        path = (self.root / relative).resolve(strict=True)
        if not path.is_relative_to(self.root) or not path.is_dir():
            raise ValueError("Choose a folder inside the configured models root")
        return path

    def files(self, relative: str) -> dict[str, str]:
        path = self.resolve(relative)
        files = {}
        for role in ("encoder", "decoder", "joiner"):
            # Also accepts standard sherpa streaming Zipformer export names.
            candidates = sorted(path.glob(role + "*.onnx"))
            preferred = path / (role + ".int8.onnx")
            if preferred in candidates:
                candidates = [preferred]
            elif path / (role + ".onnx") in candidates:
                candidates = [path / (role + ".onnx")]
            if len(candidates) != 1:
                raise ValueError("Select a folder with one encoder, decoder, joiner ONNX model and tokens.txt")
            files[role] = self._file(candidates[0], 4 * 1024**3)
        files["tokens"] = self._file(path / "tokens.txt", 16 * 1024**2)
        return files

    def _file(self, path: Path, limit: int) -> str:
        value = path.resolve(strict=True)
        if not value.is_relative_to(self.root) or not value.is_file() or not 0 < value.stat().st_size <= limit:
            raise ValueError("Missing, empty, oversized or outside-root model file")
        return str(value)

    def browse(self, relative: str) -> dict:
        path = self.resolve(relative)
        children = []
        # Never enumerate arbitrary disks or disclose absolute server paths.
        for child in path.iterdir():
            if child.name.startswith(".") or child.is_symlink() or not child.is_dir():
                continue
            children.append({"name": child.name, "path": child.relative_to(self.root).as_posix()})
            if len(children) > 200:
                raise ValueError("Too many folders here; configure a narrower models root")
        valid = True
        try:
            self.files(relative)
        except (ValueError, OSError):
            valid = False
        parent = path.parent.relative_to(self.root).as_posix() if path != self.root else None
        return {"path": path.relative_to(self.root).as_posix(), "parent": parent,
                "folders": sorted(children, key=lambda child: child["name"].casefold()),
                "compatibleFiles": valid}
