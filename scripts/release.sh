#!/bin/bash
# release.sh — Version management and release tagging
#
# Usage:
#   ./scripts/release.sh bump patch        # 1.0.0-SNAPSHOT → 1.0.0 → 1.0.1-SNAPSHOT
#   ./scripts/release.sh bump minor        # 1.0.0-SNAPSHOT → 1.0.0 → 1.1.0-SNAPSHOT
#   ./scripts/release.sh bump major        # 1.0.0-SNAPSHOT → 1.0.0 → 2.0.0-SNAPSHOT
#   ./scripts/release.sh set 1.2.3         # Set specific version
#   ./scripts/release.sh tag               # Create git tag for current version
#   ./scripts/release.sh current           # Show current version
#
# Workflow:
#   1. Bumps version in all pom.xml files
#   2. Commits version change
#   3. Creates git tag
#   4. Prepares next SNAPSHOT version

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# ── Helpers ────────────────────────────────────────────────────
log()  { echo -e "\033[1;34m▶ $1\033[0m"; }
ok()   { echo -e "\033[1;32m✓ $1\033[0m"; }
warn() { echo -e "\033[1;33m⚠ $1\033[0m"; }
err()  { echo -e "\033[1;31m✗ $1\033[0m"; exit 1; }

# ── Find all pom.xml files ─────────────────────────────────────
find_poms() {
    find "$PROJECT_DIR" -name "pom.xml" -not -path "*/target/*" -not -path "*/.mvn/*"
}

# ── Get current version from root pom.xml ──────────────────────
get_version() {
    grep -oP '<version>\K[^<]+' "$PROJECT_DIR/pom.xml" | head -1
}

# ── Set version in all pom.xml files ───────────────────────────
set_version() {
    local new_version="$1"
    log "Setting version to $new_version in all pom.xml files..."
    
    while IFS= read -r pom; do
        # Use sed to replace version in root project (not parent references)
        sed -i "s|<version>.*</version>|<version>$new_version</version>|" "$pom"
        log "  Updated: ${pom#$PROJECT_DIR/}"
    done < <(find_poms)
    
    ok "Version set to $new_version"
}

# ── Bump version ───────────────────────────────────────────────
bump_version() {
    local bump_type="$1"
    local current
    current=$(get_version)
    
    # Remove -SNAPSHOT if present
    current="${current%-SNAPSHOT}"
    
    # Parse version parts
    IFS='.' read -r major minor patch <<< "$current"
    
    case "$bump_type" in
        major)
            major=$((major + 1))
            minor=0
            patch=0
            ;;
        minor)
            minor=$((minor + 1))
            patch=0
            ;;
        patch)
            patch=$((patch + 1))
            ;;
        *)
            err "Invalid bump type: $bump_type (use major, minor, or patch)"
            ;;
    esac
    
    echo "$major.$minor.$patch"
}

# ── Create git tag ─────────────────────────────────────────────
create_tag() {
    local version
    version=$(get_version)
    version="${version%-SNAPSHOT}"
    
    local tag="v$version"
    
    log "Creating git tag: $tag"
    
    # Check if tag already exists
    if git -C "$PROJECT_DIR" rev-parse "$tag" >/dev/null 2>&1; then
        warn "Tag $tag already exists"
        return 0
    fi
    
    git -C "$PROJECT_DIR" tag -a "$tag" -m "Release $tag"
    ok "Created tag: $tag"
    echo ""
    echo "To push tag: git push origin $tag"
    echo "To push all tags: git push origin --tags"
}

# ── Show current version ───────────────────────────────────────
show_version() {
    local version
    version=$(get_version)
    echo "Current version: $version"
    
    # Show all versions
    echo ""
    echo "All pom.xml versions:"
    while IFS= read -r pom; do
        local ver
        ver=$(grep -oP '<version>\K[^<]+' "$pom" | head -1)
        echo "  ${pom#$PROJECT_DIR/}: $ver"
    done < <(find_poms)
}

# ── Main ───────────────────────────────────────────────────────
main() {
    local command="${1:-help}"
    
    case "$command" in
        bump)
            local bump_type="${2:-patch}"
            local old_version new_version
            
            old_version=$(get_version)
            new_version=$(bump_version "$bump_type")
            
            echo ""
            echo "════════════════════════════════════════════════════════"
            echo "  quad Release Script"
            echo "════════════════════════════════════════════════════════"
            echo ""
            echo "  Old version: $old_version"
            echo "  New version: $new_version"
            echo "  Bump type:   $bump_type"
            echo ""
            
            # Set release version
            set_version "$new_version"
            
            # Commit
            log "Committing version change..."
            git -C "$PROJECT_DIR" add -A
            git -C "$PROJECT_DIR" commit -m "chore: release v$new_version" || warn "Nothing to commit"
            
            # Tag
            create_tag
            
            # Prepare next SNAPSHOT
            local next_version
            IFS='.' read -r major minor patch <<< "$new_version"
            next_version="$major.$minor.$((patch + 1))-SNAPSHOT"
            
            log "Preparing next development version: $next_version"
            set_version "$next_version"
            
            git -C "$PROJECT_DIR" add -A
            git -C "$PROJECT_DIR" commit -m "chore: prepare for next development iteration ($next_version)" || warn "Nothing to commit"
            
            echo ""
            ok "Release complete!"
            echo ""
            echo "Next steps:"
            echo "  git push origin main --tags"
            echo ""
            ;;
            
        set)
            local version="${2:?Version required (e.g., 1.2.3)}"
            set_version "$version"
            ;;
            
        tag)
            create_tag
            ;;
            
        current)
            show_version
            ;;
            
        help|*)
            echo ""
            echo "quad Release Script"
            echo ""
            echo "Usage:"
            echo "  $0 bump <type>    Bump version (major, minor, patch)"
            echo "  $0 set <version>  Set specific version"
            echo "  $0 tag            Create git tag for current version"
            echo "  $0 current        Show current version"
            echo ""
            echo "Examples:"
            echo "  $0 bump patch     # 1.0.0 → 1.0.1"
            echo "  $0 bump minor     # 1.0.0 → 1.1.0"
            echo "  $0 bump major     # 1.0.0 → 2.0.0"
            echo "  $0 set 2.0.0-rc1  # Set pre-release version"
            echo ""
            ;;
    esac
}

main "$@"
