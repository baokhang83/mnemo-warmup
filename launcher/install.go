package main

import (
	"io"
	"io/fs"
	"os"
	"path/filepath"
)

// copyTree copies the regular files and directories under src into dst, keeping permission bits
// (the launcher must stay executable). Symbolic links are not part of the tools layout and are
// skipped. It can be run again over an existing copy, for example after an init container restart.
func copyTree(src, dst string) error {
	return filepath.WalkDir(src, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		target := filepath.Join(dst, rel)
		info, err := d.Info()
		if err != nil {
			return err
		}
		switch {
		case d.IsDir():
			return os.MkdirAll(target, 0o755)
		case info.Mode().IsRegular():
			return copyFile(path, target, info.Mode().Perm())
		}
		return nil
	})
}

// copyFile writes to a temporary name and renames it into place, so a reader never sees a
// half-written launcher or jar and a rerun can replace a file that is in use.
func copyFile(src, dst string, perm fs.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp, err := os.CreateTemp(filepath.Dir(dst), ".mnemo-install-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name()) // no-op once renamed
	if _, err := io.Copy(tmp, in); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Chmod(tmp.Name(), perm); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), dst)
}
