#!/usr/bin/env ruby
# Static upload-boundary checks; this does not execute a workflow or its native tests.
require 'yaml'

paths = ARGV.empty? ? Dir['.github/workflows/pos-*.yml'].sort : ARGV
raise 'No POS workflows found' if paths.empty?
uploads = 0
paths.each do |path|
  workflow = YAML.load_file(path)
  workflow.fetch('jobs').each do |job_name, job|
    steps = job.fetch('steps', [])
    publishers = steps.select { |step| step['uses'].to_s.start_with?('actions/upload-artifact@') }
    next if publishers.empty?
    gates = steps.select { |step| step['id'] == 'public_safe' }
    raise "#{path}/#{job_name}: require one public_safe gate" unless gates.length == 1
    gate = gates.first
    script = gate.fetch('run', '')
    unless gate['if'] == 'always()' && !gate['continue-on-error'] &&
           gate['shell'] == 'bash' && script.include?('set -euo pipefail') &&
           script.match?(%r{scripts/pos/(publish_public_artifact|stage_native_diagnostics)\.py})
      raise "#{path}/#{job_name}: invalid publication gate"
    end
    publishers.each do |step|
      unless step['if'] == "always() && steps.public_safe.outcome == 'success'" &&
             steps.index(gate) < steps.index(step)
        raise "#{path}/#{job_name}: upload must follow a successful publication gate"
      end
      payload = step.fetch('with').fetch('path')
      unless payload.start_with?('build/pos-public-artifacts/') &&
             !payload.include?("\n") && !payload.split('/').include?('..') &&
             script.include?("--output \"#{payload}\"")
        raise "#{path}/#{job_name}: upload must select only its validated public directory"
      end
      uploads += 1
    end
  end
end
puts "ok publication boundaries: #{uploads} upload steps in #{paths.length} POS workflows"
