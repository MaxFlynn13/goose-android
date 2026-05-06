"""
Goose utility functions available to the AI via the python tool.
These are pre-loaded and can be imported in any Python execution.
"""

import os
import json


def read_file(path):
    """Read a file and return its contents."""
    with open(path, 'r') as f:
        return f.read()


def write_file(path, content):
    """Write content to a file."""
    os.makedirs(os.path.dirname(path) if os.path.dirname(path) else '.', exist_ok=True)
    with open(path, 'w') as f:
        f.write(content)


def list_files(directory='.', recursive=False):
    """List files in a directory."""
    if recursive:
        result = []
        for root, dirs, files in os.walk(directory):
            for f in files:
                result.append(os.path.relpath(os.path.join(root, f), directory))
        return result
    else:
        return os.listdir(directory)


def parse_json(text):
    """Parse a JSON string."""
    return json.loads(text)


def to_json(obj, indent=2):
    """Convert an object to a JSON string."""
    return json.dumps(obj, indent=indent, default=str)
