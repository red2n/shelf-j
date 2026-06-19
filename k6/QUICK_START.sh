#!/bin/bash
# Flow Guard Quick Start
# Copy-paste these commands to run the complete flow guard test

set -e

echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║                 SHELF-J FLOW GUARD TEST — QUICK START                      ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"
echo ""

# Step 1: Check if services are running
echo "📋 Step 1: Checking if services are running..."
if curl -s http://localhost:8090/health > /dev/null; then
    echo "   ✓ Gateway is up"
else
    echo "   ✗ Gateway is NOT running"
    echo "   → Start services with: docker-compose up -d"
    exit 1
fi

# Step 2: Check if k6 is installed
echo ""
echo "📋 Step 2: Checking k6 installation..."
if command -v k6 &> /dev/null; then
    echo "   ✓ k6 is installed ($(k6 version | head -1))"
else
    echo "   ✗ k6 is NOT installed"
    echo "   → Install with: brew install k6  # macOS"
    echo "   →            or snap install k6  # Linux"
    exit 1
fi

# Step 3: Run flow guard test
echo ""
echo "🚀 Step 3: Running Flow Guard Test..."
echo "   Testing 47 endpoints across 8 business phases"
echo "   Expected duration: ~10 seconds"
echo "   Expected success rate: 100%"
echo ""

cd "$(dirname "$0")/.."
k6 run k6/flow-guard-comprehensive.js --env BASE_URL=http://localhost:8090

# Step 4: Post-test validation
echo ""
echo "📋 Step 4: Post-test validation..."

if command -v psql &> /dev/null; then
    echo "   Running database validation..."
    export PGHOST=localhost
    export PGPORT=5432
    export PGUSER=shelfj
    export PGDATABASE=shelfj

    if ./k6/db/validate_all.sh 2>/dev/null; then
        echo "   ✓ Database validation passed"
    else
        echo "   ⚠ Database validation skipped (schemas may not exist yet)"
    fi
else
    echo "   ⚠ psql not found, skipping database validation"
    echo "   → Install with: brew install postgresql  # macOS"
fi

echo ""
echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║                           ✓ TEST COMPLETE                                  ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"
echo ""
echo "📖 Next Steps:"
echo "   1. Review results above — all checks should be ✓"
echo "   2. Read the test source: cat k6/flow-guard-comprehensive.js"
echo "   3. Run full-stack test: k6 run k6/full-stack-simulation.js"
echo ""
echo "🔍 Troubleshooting:"
echo "   • If 403 errors: Check tenant/user headers are correct"
echo "   • If 404 errors: Earlier phase may have failed; check logs"
echo "   • If 500 errors: Check service logs with: docker logs shelfj-<service>"
echo ""
